package am.ik.kagami.storage;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3OutputStream;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@link StorageService} backed by Amazon S3 or any S3-compatible object storage.
 * Selected by {@code kagami.storage.type=s3}.
 * <p>
 * An artifact is stored as the object {@code [keyPrefix/]repositoryId/artifactPath}. A
 * "directory" is nothing but a key prefix ending with {@code /}, so directory entries
 * have neither a size nor a last-modified timestamp, and a directory exists as long as at
 * least one object lives under its prefix.
 * <p>
 * Uploads go through the {@link S3OutputStreamProvider}: the stream is buffered in memory
 * and turned into a single {@code PutObject}, or a multipart upload once it outgrows the
 * buffer, so that an input stream of unknown length can be stored without spooling it to
 * disk. Every request carries its content length and checksum, which keeps retries safe.
 */
public class S3StorageService implements StorageService {

	/**
	 * The maximum number of keys a single {@code DeleteObjects} request accepts.
	 */
	private static final int DELETE_BATCH_SIZE = 1000;

	private final S3Client s3Client;

	private final S3OutputStreamProvider outputStreamProvider;

	private final String bucket;

	/**
	 * Either empty or a prefix ending with {@code /}.
	 */
	private final String keyPrefix;

	private S3StorageService(S3Client s3Client, S3OutputStreamProvider outputStreamProvider, String bucket,
			@Nullable String keyPrefix) {
		this.s3Client = s3Client;
		this.outputStreamProvider = outputStreamProvider;
		this.bucket = bucket;
		this.keyPrefix = normalizeKeyPrefix(keyPrefix);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		String key = key(location.requireArtifactPath());
		ObjectMetadata metadata = ObjectMetadata.builder().contentType(ArtifactContentType.of(location.name())).build();
		try {
			S3OutputStream outputStream = this.outputStreamProvider.create(this.bucket, key, metadata);
			try {
				inputStream.transferTo(outputStream);
			}
			catch (IOException | RuntimeException e) {
				// Never complete an upload from a broken input; a truncated artifact must
				// not reach the bucket
				outputStream.abort();
				throw e;
			}
			outputStream.close();
		}
		catch (SdkException | io.awspring.cloud.s3.S3Exception e) {
			throw new IOException("Failed to store s3://%s/%s".formatted(this.bucket, key), e);
		}
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = key(location.requireArtifactPath());
		// A single HeadObject serves exists(), contentLength() and lastModified() of the
		// returned resource
		return headObject(key).map(head -> S3ObjectResource.builder()
			.s3Client(this.s3Client)
			.bucket(this.bucket)
			.key(key)
			.filename(location.name())
			.contentLength(head.contentLength())
			.lastModified(lastModified(head.lastModified()))
			.build());
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		String key = key(location.requireArtifactPath());
		String directoryPrefix = key + "/";
		try {
			// A single listing covers the object itself and, when the location denotes a
			// directory, every object underneath it
			List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH_SIZE);
			boolean deleted = false;
			for (ListObjectsV2Response page : this.s3Client.listObjectsV2Paginator(
					request -> request.bucket(this.bucket).prefix(key).maxKeys(DELETE_BATCH_SIZE))) {
				for (S3Object object : page.contents()) {
					// "org/example/lib" must not take "org/example/library.jar" with it
					if (object.key().equals(key) || object.key().startsWith(directoryPrefix)) {
						batch.add(ObjectIdentifier.builder().key(object.key()).build());
					}
				}
				deleted |= deleteBatch(batch, key);
			}
			return deleted;
		}
		catch (SdkException e) {
			throw new IOException("Failed to delete s3://%s/%s".formatted(this.bucket, key), e);
		}
	}

	@Override
	public boolean deleteFile(ArtifactLocation location) throws IOException {
		String key = key(location.requireArtifactPath());
		try {
			if (headObject(key).isEmpty()) {
				return false;
			}
			this.s3Client.deleteObject(request -> request.bucket(this.bucket).key(key));
			return true;
		}
		catch (NoSuchKeyException e) {
			return false;
		}
		catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return false;
			}
			throw new IOException("Failed to delete s3://%s/%s".formatted(this.bucket, key), e);
		}
		catch (SdkException e) {
			throw new IOException("Failed to delete s3://%s/%s".formatted(this.bucket, key), e);
		}
	}

	@Override
	public boolean deleteIfEmpty(ArtifactLocation location) {
		location.requireArtifactPath();
		// Object storage has no directory entry to remove. Once its last object is gone,
		// the prefix ceases to exist automatically.
		return false;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		String prefix = directoryPrefix(location);
		List<StorageEntry> entries = new ArrayList<>();
		try {
			for (ListObjectsV2Response page : this.s3Client
				.listObjectsV2Paginator(request -> request.bucket(this.bucket).prefix(prefix).delimiter("/"))) {
				for (CommonPrefix commonPrefix : page.commonPrefixes()) {
					String name = commonPrefix.prefix().substring(prefix.length(), commonPrefix.prefix().length() - 1);
					entries.add(directoryEntry(location.resolve(name)));
				}
				for (S3Object object : page.contents()) {
					String name = object.key().substring(prefix.length());
					if (name.isEmpty()) {
						// A zero-byte "directory marker" object keyed by the prefix
						// itself
						// is not a child
						continue;
					}
					entries.add(fileEntry(location.resolve(name), object.size(), object.lastModified()));
				}
			}
		}
		catch (SdkException e) {
			throw new IOException("Failed to list s3://%s/%s".formatted(this.bucket, prefix), e);
		}
		entries.sort(Comparator.comparing(StorageEntry::name));
		return List.copyOf(entries);
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		try {
			if (!location.isRoot()) {
				Optional<HeadObjectResponse> head = headObject(key(location));
				if (head.isPresent()) {
					return Optional.of(fileEntry(location, head.get().contentLength(), head.get().lastModified()));
				}
			}
			if (hasAnyObject(directoryPrefix(location))) {
				return Optional.of(directoryEntry(location));
			}
			return Optional.empty();
		}
		catch (SdkException e) {
			throw new IOException("Failed to stat s3://%s/%s".formatted(this.bucket, key(location)), e);
		}
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		String prefix = directoryPrefix(ArtifactLocation.root(repositoryId));
		StorageStats.Builder builder = StorageStats.builder();
		try {
			for (ListObjectsV2Response page : this.s3Client
				.listObjectsV2Paginator(request -> request.bucket(this.bucket).prefix(prefix))) {
				for (S3Object object : page.contents()) {
					String key = object.key();
					if (key.endsWith("/")) {
						continue;
					}
					builder.addFile(key.substring(key.lastIndexOf('/') + 1), object.size(),
							lastModified(object.lastModified()));
				}
			}
		}
		catch (SdkException e) {
			throw new IOException("Failed to list s3://%s/%s".formatted(this.bucket, prefix), e);
		}
		return builder.build();
	}

	private Optional<HeadObjectResponse> headObject(String key) {
		try {
			return Optional.of(this.s3Client.headObject(request -> request.bucket(this.bucket).key(key)));
		}
		catch (NoSuchKeyException e) {
			return Optional.empty();
		}
		catch (S3Exception e) {
			// Some S3-compatible servers answer HeadObject with a bare 404
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw e;
		}
	}

	private boolean hasAnyObject(String prefix) {
		return !this.s3Client.listObjectsV2(request -> request.bucket(this.bucket).prefix(prefix).maxKeys(1))
			.contents()
			.isEmpty();
	}

	/**
	 * Delete the collected objects in one request and empty the batch.
	 * @param batch the objects to delete; emptied by this call
	 * @param key the key the deletion started from, for the error message
	 * @return {@code true} if anything was deleted
	 */
	private boolean deleteBatch(List<ObjectIdentifier> batch, String key) throws IOException {
		if (batch.isEmpty()) {
			return false;
		}
		List<ObjectIdentifier> objects = List.copyOf(batch);
		batch.clear();
		DeleteObjectsResponse response = this.s3Client
			.deleteObjects(request -> request.bucket(this.bucket).delete(delete -> delete.objects(objects)));
		// A batched delete reports the keys it could not remove instead of failing
		if (!response.errors().isEmpty()) {
			throw new IOException("Failed to delete %d object(s) under s3://%s/%s: %s"
				.formatted(response.errors().size(), this.bucket, key, response.errors()));
		}
		return true;
	}

	private String key(ArtifactLocation location) {
		String repositoryKey = this.keyPrefix + location.repositoryId();
		return location.isRoot() ? repositoryKey : repositoryKey + "/" + location.artifactPath();
	}

	private String directoryPrefix(ArtifactLocation location) {
		return key(location) + "/";
	}

	private static StorageEntry directoryEntry(ArtifactLocation location) {
		return StorageEntry.builder()
			.name(location.name())
			.type(StorageEntryType.DIRECTORY)
			.path(location.artifactPath())
			.build();
	}

	private static StorageEntry fileEntry(ArtifactLocation location, @Nullable Long size,
			@Nullable Instant lastModified) {
		return StorageEntry.builder()
			.name(location.name())
			.type(StorageEntryType.FILE)
			.path(location.artifactPath())
			.size(size)
			.lastModified(lastModified == null ? null : lastModified(lastModified))
			.build();
	}

	/**
	 * S3 reports the modification time of an object in the {@code Last-Modified} header
	 * of {@code HeadObject} with second precision, whereas some servers list objects with
	 * millisecond precision. Truncate so that listing and stat agree.
	 */
	private static Instant lastModified(Instant lastModified) {
		return lastModified.truncatedTo(ChronoUnit.SECONDS);
	}

	private static String normalizeKeyPrefix(@Nullable String keyPrefix) {
		if (keyPrefix == null) {
			return "";
		}
		String prefix = keyPrefix.trim();
		while (prefix.startsWith("/")) {
			prefix = prefix.substring(1);
		}
		while (prefix.endsWith("/")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		return prefix.isEmpty() ? "" : prefix + "/";
	}

	public static final class Builder {

		@Nullable private S3Client s3Client;

		@Nullable private S3OutputStreamProvider outputStreamProvider;

		@Nullable private String bucket;

		@Nullable private String keyPrefix;

		private Builder() {
		}

		public Builder s3Client(S3Client s3Client) {
			this.s3Client = s3Client;
			return this;
		}

		public Builder outputStreamProvider(S3OutputStreamProvider outputStreamProvider) {
			this.outputStreamProvider = outputStreamProvider;
			return this;
		}

		public Builder bucket(@Nullable String bucket) {
			this.bucket = bucket;
			return this;
		}

		public Builder keyPrefix(@Nullable String keyPrefix) {
			this.keyPrefix = keyPrefix;
			return this;
		}

		public S3StorageService build() {
			if (!StringUtils.hasText(this.bucket)) {
				throw new IllegalStateException(
						"'kagami.storage.s3.bucket' is required when 'kagami.storage.type' is s3");
			}
			return new S3StorageService(Objects.requireNonNull(this.s3Client, "s3Client is required"),
					Objects.requireNonNull(this.outputStreamProvider, "outputStreamProvider is required"), this.bucket,
					this.keyPrefix);
		}

	}

}
