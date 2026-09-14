package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
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
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Amazon S3 (or S3-compatible) implementation of {@link StorageService}.
 * <p>
 * Artifacts are stored under the key
 * {@code [<key-prefix>/]<repository-id>/<artifact-path>}. Only the synchronous
 * {@link S3Client} is used so that unknown-length streams can be uploaded with chunked
 * encoding and no async HTTP client is required.
 */
public class S3StorageService implements StorageService {

	private static final int DELETE_BATCH_SIZE = 1000;

	private final S3Client s3Client;

	private final String bucket;

	private final String keyPrefix;

	public S3StorageService(S3Client s3Client, KagamiProperties properties) {
		KagamiProperties.Storage storage = properties.storage();
		KagamiProperties.Storage.S3 s3 = storage.s3();
		if (s3 == null || !StringUtils.hasText(s3.bucket())) {
			throw new IllegalStateException("'kagami.storage.s3.bucket' is required for S3 storage");
		}
		this.s3Client = s3Client;
		this.bucket = s3.bucket();
		this.keyPrefix = normalizeKeyPrefix(s3.keyPrefix());
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		String key = key(location.requireArtifactPath());
		String contentType = contentType(location.artifactPath());
		PutObjectRequest request = PutObjectRequest.builder()
			.bucket(this.bucket)
			.key(key)
			.contentType(contentType)
			.build();
		try {
			this.s3Client.putObject(request, RequestBody.fromContentProvider(() -> inputStream, contentType));
		}
		catch (SdkException e) {
			throw new IOException("Failed to store " + key, e);
		}
	}

	/**
	 * Runs a single {@code HeadObject} and caches the result in the returned resource, so
	 * {@code exists()} and {@code contentLength()} serve from that one call instead of
	 * triggering their own.
	 */
	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = key(location.requireArtifactPath());
		try {
			HeadObjectResponse head = this.s3Client
				.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return Optional.of(new S3ArtifactResource(key, location.name(), head.contentLength(), head.lastModified()));
		}
		catch (NoSuchKeyException e) {
			return Optional.empty();
		}
		catch (SdkException e) {
			throw new IllegalStateException("Failed to retrieve " + key, e);
		}
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		try {
			List<String> keys = new ArrayList<>();
			if (location.isRoot()) {
				keys.addAll(listKeys(repositoryBase(location.repositoryId()) + "/"));
			}
			else {
				String key = key(location);
				if (exists(key)) {
					keys.add(key);
				}
				keys.addAll(listKeys(key + "/"));
			}
			if (keys.isEmpty()) {
				return false;
			}
			for (int i = 0; i < keys.size(); i += DELETE_BATCH_SIZE) {
				List<ObjectIdentifier> batch = keys.subList(i, Math.min(i + DELETE_BATCH_SIZE, keys.size()))
					.stream()
					.map(k -> ObjectIdentifier.builder().key(k).build())
					.toList();
				this.s3Client.deleteObjects(DeleteObjectsRequest.builder()
					.bucket(this.bucket)
					.delete(Delete.builder().objects(batch).build())
					.build());
			}
			return true;
		}
		catch (SdkException e) {
			throw new IOException("Failed to delete " + location, e);
		}
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		String prefix = listingPrefix(location);
		try {
			List<StorageEntry> entries = new ArrayList<>();
			String continuationToken = null;
			do {
				ListObjectsV2Response response = this.s3Client.listObjectsV2(ListObjectsV2Request.builder()
					.bucket(this.bucket)
					.prefix(prefix)
					.delimiter("/")
					.continuationToken(continuationToken)
					.build());
				for (CommonPrefix commonPrefix : response.commonPrefixes()) {
					String childName = commonPrefix.prefix()
						.substring(prefix.length(), commonPrefix.prefix().length() - 1);
					entries.add(StorageEntry.builder()
						.name(childName)
						.type(StorageEntryType.DIRECTORY)
						.path(childPath(location, childName))
						.build());
				}
				for (S3Object object : response.contents()) {
					String childName = object.key().substring(prefix.length());
					if (!StringUtils.hasText(childName) || childName.contains("/")) {
						continue;
					}
					entries.add(StorageEntry.builder()
						.name(childName)
						.type(StorageEntryType.FILE)
						.path(childPath(location, childName))
						.size(object.size())
						.lastModified(object.lastModified())
						.build());
				}
				continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
			}
			while (continuationToken != null);
			entries.sort(Comparator.comparing(StorageEntry::name));
			return entries;
		}
		catch (SdkException e) {
			throw new IOException("Failed to list " + location, e);
		}
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		try {
			if (location.isRoot()) {
				if (listKeys(repositoryBase(location.repositoryId()) + "/", 1).isEmpty()) {
					return Optional.empty();
				}
				return Optional
					.of(StorageEntry.builder().name(location.name()).type(StorageEntryType.DIRECTORY).path("").build());
			}
			String key = key(location);
			try {
				HeadObjectResponse head = this.s3Client
					.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
				return Optional.of(StorageEntry.builder()
					.name(location.name())
					.type(StorageEntryType.FILE)
					.path(location.artifactPath())
					.size(head.contentLength())
					.lastModified(head.lastModified())
					.build());
			}
			catch (NoSuchKeyException e) {
				if (listKeys(key + "/", 1).isEmpty()) {
					return Optional.empty();
				}
				return Optional.of(StorageEntry.builder()
					.name(location.name())
					.type(StorageEntryType.DIRECTORY)
					.path(location.artifactPath())
					.build());
			}
		}
		catch (SdkException e) {
			throw new IOException("Failed to stat " + location, e);
		}
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		try {
			StorageStats.Builder builder = StorageStats.builder();
			String continuationToken = null;
			do {
				ListObjectsV2Response response = this.s3Client.listObjectsV2(ListObjectsV2Request.builder()
					.bucket(this.bucket)
					.prefix(repositoryBase(repositoryId) + "/")
					.continuationToken(continuationToken)
					.build());
				for (S3Object object : response.contents()) {
					String fileName = object.key().substring(object.key().lastIndexOf('/') + 1);
					// HeadObject reports second precision (HTTP-date), so truncate here
					// to
					// stay consistent with stat()
					Instant lastModified = object.lastModified();
					builder.addFile(fileName, object.size(),
							(lastModified != null) ? lastModified.truncatedTo(ChronoUnit.SECONDS) : null);
				}
				continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
			}
			while (continuationToken != null);
			return builder.build();
		}
		catch (SdkException e) {
			throw new IOException("Failed to calculate stats of repository " + repositoryId, e);
		}
	}

	private boolean exists(String key) {
		try {
			this.s3Client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return true;
		}
		catch (NoSuchKeyException e) {
			return false;
		}
	}

	private List<String> listKeys(String prefix) {
		return listKeys(prefix, null);
	}

	private List<String> listKeys(String prefix, @Nullable Integer maxKeys) {
		List<String> keys = new ArrayList<>();
		String continuationToken = null;
		do {
			ListObjectsV2Response response = this.s3Client.listObjectsV2(ListObjectsV2Request.builder()
				.bucket(this.bucket)
				.prefix(prefix)
				.maxKeys(maxKeys)
				.continuationToken(continuationToken)
				.build());
			for (S3Object object : response.contents()) {
				keys.add(object.key());
				if (maxKeys != null && keys.size() >= maxKeys) {
					return keys;
				}
			}
			continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
		}
		while (continuationToken != null);
		return keys;
	}

	private String key(ArtifactLocation location) {
		return repositoryBase(location.repositoryId()) + "/" + location.artifactPath();
	}

	private String listingPrefix(ArtifactLocation location) {
		return location.isRoot() ? repositoryBase(location.repositoryId()) + "/" : key(location) + "/";
	}

	private String repositoryBase(String repositoryId) {
		return this.keyPrefix.isEmpty() ? repositoryId : this.keyPrefix + "/" + repositoryId;
	}

	private static String childPath(ArtifactLocation location, String childName) {
		return location.isRoot() ? childName : location.artifactPath() + "/" + childName;
	}

	private static String normalizeKeyPrefix(@Nullable String keyPrefix) {
		if (!StringUtils.hasText(keyPrefix)) {
			return "";
		}
		String prefix = keyPrefix.trim();
		while (prefix.startsWith("/")) {
			prefix = prefix.substring(1);
		}
		while (prefix.endsWith("/")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		return prefix;
	}

	static String contentType(String artifactPath) {
		if (artifactPath.endsWith(".jar")) {
			return "application/java-archive";
		}
		else if (artifactPath.endsWith(".pom") || artifactPath.endsWith(".xml")) {
			return "application/xml";
		}
		else if (artifactPath.endsWith(".sha1") || artifactPath.endsWith(".md5") || artifactPath.endsWith(".sha256")
				|| artifactPath.endsWith(".sha512")) {
			return "text/plain";
		}
		else if (artifactPath.endsWith(".asc")) {
			return "application/pgp-signature";
		}
		return "application/octet-stream";
	}

	/**
	 * A resource backed by a single {@code HeadObject} result. Content is fetched lazily
	 * on {@link #getInputStream()}.
	 */
	private final class S3ArtifactResource extends AbstractResource {

		private final String key;

		private final String filename;

		private final long contentLength;

		private final Instant lastModified;

		private S3ArtifactResource(String key, String filename, long contentLength, Instant lastModified) {
			this.key = key;
			this.filename = filename;
			this.contentLength = contentLength;
			this.lastModified = lastModified;
		}

		@Override
		public String getDescription() {
			return "S3 resource [%s/%s]".formatted(S3StorageService.this.bucket, this.key);
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public long contentLength() {
			return this.contentLength;
		}

		@Override
		public long lastModified() {
			return this.lastModified.toEpochMilli();
		}

		@Override
		public @Nullable String getFilename() {
			return this.filename;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			try {
				ResponseInputStream<GetObjectResponse> response = S3StorageService.this.s3Client
					.getObject(GetObjectRequest.builder().bucket(S3StorageService.this.bucket).key(this.key).build());
				return response;
			}
			catch (SdkException e) {
				throw new IOException("Failed to read " + this.key, e);
			}
		}

	}

}
