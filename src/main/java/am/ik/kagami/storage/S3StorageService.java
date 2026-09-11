package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@link StorageService} implementation backed by Amazon S3 or any S3-compatible object
 * storage. Object keys are laid out as {@code [keyPrefix/]{repositoryId}/{artifactPath}},
 * so a "directory" is a common key prefix rather than a real object.
 * <p>
 * The synchronous {@link S3Client} auto-configured by Spring Cloud AWS is used directly
 * for {@code putObject}, {@code headObject}, {@code listObjectsV2} and
 * {@code deleteObjects}.
 */
public class S3StorageService implements StorageService {

	private static final int MAX_KEYS_PER_DELETE = 1000;

	private final S3Client s3Client;

	private final String bucket;

	private final String keyPrefix;

	public S3StorageService(S3Client s3Client, KagamiProperties.Storage storage) {
		this.s3Client = s3Client;
		KagamiProperties.Storage.S3 s3 = storage.s3();
		this.bucket = Objects.requireNonNull(s3.bucket(),
				"'kagami.storage.s3.bucket' is required for the S3 storage backend");
		this.keyPrefix = normalizePrefix(s3.keyPrefix());
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		String key = keyFor(location.requireArtifactPath());
		String contentType = contentTypeFor(location.artifactPath());
		// The length is unknown, so stream the body without a content length
		RequestBody body = RequestBody.fromContentProvider(() -> inputStream, contentType);
		this.s3Client.putObject(request -> request.bucket(this.bucket).key(key).contentType(contentType), body);
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = keyFor(location.requireArtifactPath());
		// A single HeadObject serves both exists() and contentLength() of the resource
		return Optional.ofNullable(headObject(key))
			.map(head -> new S3ObjectResource(this.s3Client, this.bucket, key, head));
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		String key = keyFor(location);
		if (headObject(key) != null) {
			this.s3Client.deleteObject(request -> request.bucket(this.bucket).key(key));
			return true;
		}
		List<String> keys = listKeys(key + "/");
		if (keys.isEmpty()) {
			return false;
		}
		deleteKeys(keys);
		return true;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		String listPrefix = keyFor(location) + "/";
		String repositoryPrefix = repositoryKey(location.repositoryId()) + "/";
		List<StorageEntry> entries = new ArrayList<>();
		listObjects(listPrefix, "/", response -> {
			for (CommonPrefix commonPrefix : response.commonPrefixes()) {
				String path = relativePath(commonPrefix.prefix(), repositoryPrefix);
				entries.add(directoryEntry(path));
			}
			for (S3Object object : response.contents()) {
				if (object.key().equals(listPrefix)) {
					// A zero-byte object that marks the directory itself
					continue;
				}
				String path = relativePath(object.key(), repositoryPrefix);
				entries.add(fileEntry(path, object));
			}
		});
		entries.sort(Comparator.comparing(StorageEntry::name));
		return entries;
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		String key = keyFor(location);
		HeadObjectResponse head = headObject(key);
		if (head != null) {
			return Optional.of(StorageEntry.builder()
				.name(location.name())
				.type(StorageEntryType.FILE)
				.path(location.artifactPath())
				.size(head.contentLength())
				// HeadObject carries sub-second precision that ListObjectsV2 truncates;
				// truncate so that stat and stats agree
				.lastModified(head.lastModified().truncatedTo(ChronoUnit.SECONDS))
				.build());
		}
		if (hasObjects(key + "/")) {
			// Object storage has no timestamp for a common prefix
			return Optional.of(StorageEntry.builder()
				.name(location.name())
				.type(StorageEntryType.DIRECTORY)
				.path(location.artifactPath())
				.size(null)
				.lastModified(null)
				.build());
		}
		return Optional.empty();
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		StorageStats.Builder builder = StorageStats.builder();
		listObjects(repositoryKey(repositoryId) + "/", null, response -> {
			for (S3Object object : response.contents()) {
				builder.addFile(name(object.key()), Objects.requireNonNullElse(object.size(), 0L),
						truncate(object.lastModified()));
			}
		});
		return builder.build();
	}

	private @Nullable HeadObjectResponse headObject(String key) {
		try {
			return this.s3Client.headObject(request -> request.bucket(this.bucket).key(key));
		}
		catch (NoSuchKeyException e) {
			return null;
		}
		catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return null;
			}
			throw e;
		}
	}

	private boolean hasObjects(String prefix) {
		ListObjectsV2Response response = this.s3Client
			.listObjectsV2(request -> request.bucket(this.bucket).prefix(prefix).maxKeys(1));
		return !response.contents().isEmpty();
	}

	private List<String> listKeys(String prefix) {
		List<String> keys = new ArrayList<>();
		listObjects(prefix, null, response -> response.contents().forEach(object -> keys.add(object.key())));
		return keys;
	}

	private void deleteKeys(List<String> keys) throws IOException {
		for (int i = 0; i < keys.size(); i += MAX_KEYS_PER_DELETE) {
			List<ObjectIdentifier> batch = keys.subList(i, Math.min(i + MAX_KEYS_PER_DELETE, keys.size()))
				.stream()
				.map(key -> ObjectIdentifier.builder().key(key).build())
				.toList();
			DeleteObjectsResponse response = this.s3Client
				.deleteObjects(request -> request.bucket(this.bucket).delete(Delete.builder().objects(batch).build()));
			if (!response.errors().isEmpty()) {
				throw new IOException("Failed to delete objects: " + response.errors());
			}
		}
	}

	/**
	 * Walk every object under {@code prefix}, following continuation tokens, and hand
	 * each page to the consumer. A {@code null} delimiter lists objects recursively.
	 */
	private void listObjects(String prefix, @Nullable String delimiter, Consumer<ListObjectsV2Response> consumer) {
		String continuationToken = null;
		do {
			ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix);
			if (delimiter != null) {
				builder.delimiter(delimiter);
			}
			if (continuationToken != null) {
				builder.continuationToken(continuationToken);
			}
			ListObjectsV2Response response = this.s3Client.listObjectsV2(builder.build());
			consumer.accept(response);
			continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
		}
		while (continuationToken != null);
	}

	private String repositoryKey(String repositoryId) {
		return this.keyPrefix.isEmpty() ? repositoryId : this.keyPrefix + "/" + repositoryId;
	}

	private String keyFor(ArtifactLocation location) {
		String repositoryKey = repositoryKey(location.repositoryId());
		return location.isRoot() ? repositoryKey : repositoryKey + "/" + location.artifactPath();
	}

	private static StorageEntry directoryEntry(String path) {
		return StorageEntry.builder()
			.name(name(path))
			.type(StorageEntryType.DIRECTORY)
			.path(path)
			.size(null)
			.lastModified(null)
			.build();
	}

	private static StorageEntry fileEntry(String path, S3Object object) {
		return StorageEntry.builder()
			.name(name(path))
			.type(StorageEntryType.FILE)
			.path(path)
			.size(object.size())
			.lastModified(truncate(object.lastModified()))
			.build();
	}

	/**
	 * Drop the sub-second part of a timestamp so that {@code stat} (HeadObject) and
	 * {@code stats} (ListObjectsV2) report the same instant regardless of the S3
	 * implementation's precision.
	 */
	private static @Nullable Instant truncate(@Nullable Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
	}

	private static String relativePath(String key, String repositoryPrefix) {
		String path = key.startsWith(repositoryPrefix) ? key.substring(repositoryPrefix.length()) : key;
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	private static String name(String path) {
		int lastSlash = path.lastIndexOf('/');
		return lastSlash < 0 ? path : path.substring(lastSlash + 1);
	}

	private static String normalizePrefix(@Nullable String prefix) {
		if (!StringUtils.hasText(prefix)) {
			return "";
		}
		String normalized = prefix.trim();
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private static String contentTypeFor(String artifactPath) {
		if (artifactPath.endsWith(".jar")) {
			return "application/java-archive";
		}
		if (artifactPath.endsWith(".pom") || artifactPath.endsWith(".xml")) {
			return "application/xml";
		}
		if (artifactPath.endsWith(".sha1") || artifactPath.endsWith(".sha256") || artifactPath.endsWith(".md5")
				|| artifactPath.endsWith(".sha512")) {
			return "text/plain";
		}
		if (artifactPath.endsWith(".asc")) {
			return "application/pgp-signature";
		}
		return "application/octet-stream";
	}

	/**
	 * A read-only {@link Resource} backed by a cached {@code HeadObject} response, so
	 * that {@link #exists()} and {@link #contentLength()} do not each issue their own
	 * request.
	 */
	private static final class S3ObjectResource extends AbstractResource {

		private final S3Client s3Client;

		private final String bucket;

		private final String key;

		private final long contentLength;

		private final Instant lastModified;

		private S3ObjectResource(S3Client s3Client, String bucket, String key, HeadObjectResponse head) {
			this.s3Client = s3Client;
			this.bucket = bucket;
			this.key = key;
			this.contentLength = Objects.requireNonNull(head.contentLength(), "contentLength is required");
			this.lastModified = Objects.requireNonNull(head.lastModified(), "lastModified is required")
				.truncatedTo(ChronoUnit.SECONDS);
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
		public String getFilename() {
			return name(this.key);
		}

		@Override
		public String getDescription() {
			return "S3 object [%s]".formatted(this.key);
		}

		@Override
		public InputStream getInputStream() {
			return this.s3Client.getObject(request -> request.bucket(this.bucket).key(this.key));
		}

	}

}
