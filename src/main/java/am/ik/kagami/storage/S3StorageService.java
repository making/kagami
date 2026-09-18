package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Amazon S3 (and S3-compatible object storage) implementation of {@link StorageService}.
 * <p>
 * Objects are stored under a key of the form
 * {@code [key-prefix]{repositoryId}/{artifactPath}}, so a byte-for-byte Maven layout is
 * preserved within the bucket. Directory listing uses {@code delimiter = "/"} because
 * {@link S3Template} has no delimiter support; the rest of the operations reuse either
 * {@link S3Template} or the low-level {@link S3Client} directly.
 */
public class S3StorageService implements StorageService {

	private static final int DELETE_BATCH_SIZE = 1000;

	private final S3Client s3Client;

	private final S3Template s3Template;

	private final String bucket;

	private final String keyPrefix;

	public S3StorageService(S3Client s3Client, S3Template s3Template, KagamiProperties properties) {
		this.s3Client = s3Client;
		this.s3Template = s3Template;
		String bucket = properties.storage().s3().bucket();
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("'kagami.storage.s3.bucket' is required when kagami.storage.type=s3");
		}
		this.bucket = bucket;
		this.keyPrefix = properties.storage().s3().keyPrefix() == null ? "" : properties.storage().s3().keyPrefix();
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		ObjectMetadata metadata = ObjectMetadata.builder().contentType(contentType(location.name())).build();
		this.s3Template.upload(this.bucket, toKey(location.requireArtifactPath()), inputStream, metadata);
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = toKey(location.requireArtifactPath());
		if (!this.s3Template.objectExists(this.bucket, key)) {
			return Optional.empty();
		}
		return Optional.of(new S3FileResource(this.s3Template.download(this.bucket, key), location.name()));
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		String key = toKey(location);
		if (objectExists(key)) {
			this.s3Template.deleteObject(this.bucket, key);
			return true;
		}
		List<String> keys = listAllKeys(key + "/");
		if (keys.isEmpty()) {
			return false;
		}
		deleteKeys(keys);
		return true;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		return listChildren(location, dirPrefix(location)).stream()
			.sorted(Comparator.comparing(StorageEntry::name))
			.toList();
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		String key = toKey(location);
		try {
			HeadObjectResponse response = this.s3Client
				.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return Optional.of(fileEntry(location.name(), location.artifactPath(), response.contentLength(),
					response.lastModified()));
		}
		catch (NoSuchKeyException e) {
			if (hasObjects(key + "/")) {
				return Optional.of(dirEntry(location.name(), location.artifactPath()));
			}
			return Optional.empty();
		}
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		StorageStats.Builder builder = StorageStats.builder();
		String prefix = basePrefix(repositoryId);
		String token = null;
		do {
			ListObjectsV2Response response = listObjectsV2(prefix, null, token);
			for (S3Object object : response.contents()) {
				String key = object.key();
				String name = key.substring(key.lastIndexOf('/') + 1);
				builder.addFile(name, object.size(), toSeconds(object.lastModified()));
			}
			token = response.nextContinuationToken();
		}
		while (token != null);
		return builder.build();
	}

	private List<StorageEntry> listChildren(ArtifactLocation location, String prefix) {
		List<StorageEntry> entries = new ArrayList<>();
		String base = basePrefix(location.repositoryId());
		String token = null;
		do {
			ListObjectsV2Response response = listObjectsV2(prefix, "/", token);
			for (CommonPrefix commonPrefix : response.commonPrefixes()) {
				String relative = stripBase(commonPrefix.prefix(), base);
				entries.add(dirEntry(nameOf(relative), relative));
			}
			for (S3Object object : response.contents()) {
				String relative = stripBase(object.key(), base);
				entries.add(fileEntry(nameOf(relative), relative, object.size(), object.lastModified()));
			}
			token = response.nextContinuationToken();
		}
		while (token != null);
		return entries;
	}

	private ListObjectsV2Response listObjectsV2(String prefix, @Nullable String delimiter,
			@Nullable String continuationToken) {
		ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix);
		if (delimiter != null) {
			builder.delimiter(delimiter);
		}
		if (continuationToken != null) {
			builder.continuationToken(continuationToken);
		}
		return this.s3Client.listObjectsV2(builder.build());
	}

	private boolean objectExists(String key) {
		try {
			this.s3Client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return true;
		}
		catch (NoSuchKeyException e) {
			return false;
		}
	}

	private boolean hasObjects(String prefix) {
		ListObjectsV2Response response = this.s3Client
			.listObjectsV2(ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix).maxKeys(1).build());
		return !response.contents().isEmpty() || !response.commonPrefixes().isEmpty();
	}

	private List<String> listAllKeys(String prefix) {
		List<String> keys = new ArrayList<>();
		String token = null;
		do {
			ListObjectsV2Response response = listObjectsV2(prefix, null, token);
			response.contents().forEach(object -> keys.add(object.key()));
			token = response.nextContinuationToken();
		}
		while (token != null);
		return keys;
	}

	private void deleteKeys(List<String> keys) {
		for (int i = 0; i < keys.size(); i += DELETE_BATCH_SIZE) {
			List<ObjectIdentifier> identifiers = keys.subList(i, Math.min(keys.size(), i + DELETE_BATCH_SIZE))
				.stream()
				.map(key -> ObjectIdentifier.builder().key(key).build())
				.toList();
			this.s3Client.deleteObjects(DeleteObjectsRequest.builder()
				.bucket(this.bucket)
				.delete(Delete.builder().objects(identifiers).build())
				.build());
		}
	}

	private String toKey(ArtifactLocation location) {
		String path = location.artifactPath();
		return this.keyPrefix + location.repositoryId() + (path.isEmpty() ? "" : "/" + path);
	}

	private String dirPrefix(ArtifactLocation location) {
		String path = location.artifactPath();
		return this.keyPrefix + location.repositoryId() + "/" + (path.isEmpty() ? "" : path + "/");
	}

	private String basePrefix(String repositoryId) {
		return this.keyPrefix + repositoryId + "/";
	}

	private String stripBase(String key, String base) {
		String relative = key.substring(base.length());
		return relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
	}

	private static String nameOf(String path) {
		int lastSlash = path.lastIndexOf('/');
		return lastSlash < 0 ? path : path.substring(lastSlash + 1);
	}

	private static StorageEntry fileEntry(String name, String path, long size, Instant lastModified) {
		return StorageEntry.builder()
			.name(name)
			.type(StorageEntryType.FILE)
			.path(path)
			.size(size)
			.lastModified(lastModified)
			.build();
	}

	private static StorageEntry dirEntry(String name, String path) {
		return StorageEntry.builder().name(name).type(StorageEntryType.DIRECTORY).path(path).build();
	}

	private static @Nullable Instant toSeconds(@Nullable Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
	}

	private static String contentType(String name) {
		if (name.endsWith(".jar")) {
			return "application/java-archive";
		}
		else if (name.endsWith(".pom") || name.endsWith(".xml")) {
			return "application/xml";
		}
		else if (name.endsWith(".sha1") || name.endsWith(".md5") || name.endsWith(".sha256")
				|| name.endsWith(".sha512")) {
			return "text/plain";
		}
		else if (name.endsWith(".asc")) {
			return "application/pgp-signature";
		}
		else {
			return "application/octet-stream";
		}
	}

	/**
	 * Wraps an {@link S3Resource} but exposes the file name only, because the underlying
	 * resource returns the whole object key as its file name.
	 */
	static final class S3FileResource extends AbstractResource {

		private final S3Resource delegate;

		private final String filename;

		S3FileResource(S3Resource delegate, String filename) {
			this.delegate = delegate;
			this.filename = filename;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return this.delegate.getInputStream();
		}

		@Override
		public boolean exists() {
			return this.delegate.exists();
		}

		@Override
		public long contentLength() throws IOException {
			return this.delegate.contentLength();
		}

		@Override
		public String getFilename() {
			return this.filename;
		}

		@Override
		public String getDescription() {
			return this.delegate.getDescription();
		}

	}

}
