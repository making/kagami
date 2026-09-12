package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.S3;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import io.awspring.cloud.s3.S3Resource;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3-backed {@link StorageService}. Artifacts are stored as flat objects under the key
 * {@code [keyPrefix]{repositoryId}/{artifactPath}}. "Directories" are represented by the
 * common prefixes of those keys, so listing uses {@code ListObjectsV2} with a {@code /}
 * delimiter, and deleting a directory removes every object under its prefix.
 */
public class S3StorageService implements StorageService {

	private static final String DELIMITER = "/";

	private static final int BATCH_SIZE = 1000;

	private final S3Client s3Client;

	private final S3OutputStreamProvider s3OutputStreamProvider;

	private final String bucket;

	private final String keyPrefix;

	public S3StorageService(KagamiProperties properties, S3Client s3Client,
			S3OutputStreamProvider s3OutputStreamProvider) {
		this.s3Client = s3Client;
		this.s3OutputStreamProvider = s3OutputStreamProvider;
		S3 s3 = properties.storage().s3();
		if (s3 == null) {
			throw new IllegalStateException("kagami.storage.s3 is required when kagami.storage.type=s3");
		}
		this.bucket = Objects.requireNonNull(s3.bucket(), "kagami.storage.s3.bucket is required");
		String prefix = s3.keyPrefix();
		this.keyPrefix = (prefix == null) ? "" : prefix.endsWith(DELIMITER) ? prefix : prefix + DELIMITER;
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		String key = objectKey(location.requireArtifactPath());
		String contentType = contentTypeFor(location.artifactPath());
		this.s3Client.putObject(builder -> builder.bucket(this.bucket).key(key).contentType(contentType),
				RequestBody.fromContentProvider(ContentStreamProvider.fromInputStream(inputStream), contentType));
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = objectKey(location.requireArtifactPath());
		KagamiS3Resource resource = new KagamiS3Resource(this.bucket, key, this.s3Client, this.s3OutputStreamProvider);
		// exists() triggers a single HeadObject whose result is cached, so the subsequent
		// contentLength() in the controller does not issue another request
		return resource.exists() ? Optional.of(resource) : Optional.empty();
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		String objectKey = objectKey(location);
		boolean found = false;
		if (!location.isRoot()) {
			// Delete the object at this exact key, if it exists
			try {
				this.s3Client.headObject(head -> head.bucket(this.bucket).key(objectKey));
				this.s3Client.deleteObject(del -> del.bucket(this.bucket).key(objectKey));
				found = true;
			}
			catch (NoSuchKeyException e) {
				// Not an object; only the objects under it (if any) remain
			}
		}
		// Delete everything under the location
		List<S3Object> objects = listAllObjects(directoryPrefix(location));
		if (!objects.isEmpty()) {
			deleteInBatches(objects);
			found = true;
		}
		return found;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		String repositoryRootPrefix = this.keyPrefix + location.repositoryId() + DELIMITER;
		String prefix = directoryPrefix(location);
		ListObjectsV2Response response = this.s3Client.listObjectsV2(
				ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix).delimiter(DELIMITER).build());

		List<StorageEntry> entries = new ArrayList<>();
		for (CommonPrefix commonPrefix : response.commonPrefixes()) {
			String path = stripTrailingSlash(commonPrefix.prefix().substring(repositoryRootPrefix.length()));
			entries.add(StorageEntry.builder()
				.name(lastSegment(path))
				.type(StorageEntryType.DIRECTORY)
				.path(path)
				.size(null)
				.lastModified(null)
				.build());
		}
		for (S3Object object : response.contents()) {
			String path = object.key().substring(repositoryRootPrefix.length());
			entries.add(StorageEntry.builder()
				.name(lastSegment(path))
				.type(StorageEntryType.FILE)
				.path(path)
				.size(object.size())
				.lastModified(truncateToSeconds(object.lastModified()))
				.build());
		}
		entries.sort(Comparator.comparing(StorageEntry::name));
		return entries;
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		String objectKey = objectKey(location);
		if (!location.isRoot()) {
			// A file object
			try {
				var headObject = this.s3Client.headObject(head -> head.bucket(this.bucket).key(objectKey));
				return Optional.of(StorageEntry.builder()
					.name(location.name())
					.type(StorageEntryType.FILE)
					.path(location.artifactPath())
					.size(headObject.contentLength())
					.lastModified(truncateToSeconds(headObject.lastModified()))
					.build());
			}
			catch (NoSuchKeyException e) {
				// May be a "directory" prefix instead of an object
			}
		}
		// A "directory" is a prefix that has at least one child
		ListObjectsV2Response response = this.s3Client.listObjectsV2(ListObjectsV2Request.builder()
			.bucket(this.bucket)
			.prefix(directoryPrefix(location))
			.delimiter(DELIMITER)
			.build());
		boolean hasChildren = !response.contents().isEmpty() || !response.commonPrefixes().isEmpty();
		if (!hasChildren) {
			return Optional.empty();
		}
		return Optional.of(StorageEntry.builder()
			.name(location.name())
			.type(StorageEntryType.DIRECTORY)
			.path(location.artifactPath())
			.size(null)
			.lastModified(null)
			.build());
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		String prefix = this.keyPrefix + repositoryId + DELIMITER;
		StorageStats.Builder builder = StorageStats.builder();
		List<S3Object> objects = listAllObjects(prefix);
		if (objects.isEmpty()) {
			return StorageStats.EMPTY;
		}
		for (S3Object object : objects) {
			builder.addFile(lastSegment(object.key().substring(prefix.length())), object.size(),
					truncateToSeconds(object.lastModified()));
		}
		return builder.build();
	}

	// An object key is the flat key of a single object:
	// {keyPrefix}{repositoryId}/{artifactPath}.
	private String objectKey(ArtifactLocation location) {
		return this.keyPrefix + location.repositoryId() + DELIMITER + location.artifactPath();
	}

	// The prefix (with a trailing slash) that selects everything at or under a location.
	private String directoryPrefix(ArtifactLocation location) {
		String base = this.keyPrefix + location.repositoryId() + DELIMITER;
		return location.isRoot() ? base : base + location.artifactPath() + DELIMITER;
	}

	private List<S3Object> listAllObjects(String prefix) {
		List<S3Object> objects = new ArrayList<>();
		String token = null;
		do {
			ListObjectsV2Request request = ListObjectsV2Request.builder()
				.bucket(this.bucket)
				.prefix(prefix)
				.continuationToken(token)
				.build();
			ListObjectsV2Response page = this.s3Client.listObjectsV2(request);
			objects.addAll(page.contents());
			token = page.isTruncated() ? page.nextContinuationToken() : null;
		}
		while (token != null);
		return objects;
	}

	private void deleteInBatches(List<S3Object> objects) {
		List<String> keys = objects.stream().map(S3Object::key).toList();
		for (int i = 0; i < keys.size(); i += BATCH_SIZE) {
			List<String> chunk = keys.subList(i, Math.min(i + BATCH_SIZE, keys.size()));
			List<ObjectIdentifier> identifiers = chunk.stream()
				.map(key -> ObjectIdentifier.builder().key(key).build())
				.toList();
			this.s3Client.deleteObjects(DeleteObjectsRequest.builder()
				.bucket(this.bucket)
				.delete(delete -> delete.objects(identifiers))
				.build());
		}
	}

	private static String stripTrailingSlash(String value) {
		while (value.endsWith(DELIMITER)) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	// S3 stores object timestamps with sub-second precision, but a HeadObject response
	// reports them in whole seconds. Truncating the list timestamps keeps the two sources
	// (list and head) consistent.
	private static Instant truncateToSeconds(Instant instant) {
		return instant.truncatedTo(ChronoUnit.SECONDS);
	}

	private static String lastSegment(String path) {
		int lastSlash = path.lastIndexOf(DELIMITER);
		return lastSlash < 0 ? path : path.substring(lastSlash + 1);
	}

	private static String contentTypeFor(String path) {
		if (path.endsWith(".jar")) {
			return MediaType.parseMediaType("application/java-archive").toString();
		}
		if (path.endsWith(".pom") || path.endsWith(".xml")) {
			return MediaType.APPLICATION_XML.toString();
		}
		if (path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256") || path.endsWith(".sha512")) {
			return MediaType.TEXT_PLAIN.toString();
		}
		if (path.endsWith(".asc")) {
			return MediaType.parseMediaType("application/pgp-signature").toString();
		}
		return MediaType.APPLICATION_OCTET_STREAM.toString();
	}

	/**
	 * {@link S3Resource} whose {@link #getFilename()} is the last segment of the object
	 * key rather than the full key, matching the filename semantics of the local backend.
	 */
	private static final class KagamiS3Resource extends S3Resource {

		private final String fileName;

		KagamiS3Resource(String bucket, String key, S3Client s3Client, S3OutputStreamProvider provider) {
			super(bucket, key, s3Client, provider);
			this.fileName = lastSegment(key);
		}

		@Override
		public String getFilename() {
			return this.fileName;
		}

	}

}
