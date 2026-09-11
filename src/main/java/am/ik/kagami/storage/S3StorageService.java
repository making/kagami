package am.ik.kagami.storage;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Exception;
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
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3 implementation of {@link StorageService}, usable with Amazon S3 and any
 * S3-compatible object storage.
 * <p>
 * Object storage has no directories, so a directory is a key prefix that at least one
 * object lives under: listing uses {@code ListObjectsV2} with {@code delimiter = "/"} and
 * maps the returned common prefixes to directory entries, which consequently carry no
 * last modification timestamp.
 */
public class S3StorageService implements StorageService {

	/**
	 * The maximum number of keys a single {@code DeleteObjects} request accepts.
	 */
	private static final int DELETE_BATCH_SIZE = 1000;

	private final S3Client s3Client;

	private final S3OutputStreamProvider outputStreamProvider;

	private final String bucket;

	private final String keyPrefix;

	private S3StorageService(S3Client s3Client, S3OutputStreamProvider outputStreamProvider, String bucket,
			String keyPrefix) {
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
		String key = objectKey(location.requireArtifactPath());
		ObjectMetadata metadata = ObjectMetadata.builder().contentType(ArtifactContentType.of(location.name())).build();
		// The stream length is unknown, so the provider buffers the content and switches
		// to a multipart upload once it exceeds a single part
		try (S3OutputStream outputStream = this.outputStreamProvider.create(this.bucket, key, metadata)) {
			inputStream.transferTo(outputStream);
		}
		catch (SdkException | S3Exception e) {
			throw new IOException("Failed to upload s3://%s/%s".formatted(this.bucket, key), e);
		}
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = objectKey(location.requireArtifactPath());
		// The HeadObject that proves the object exists also supplies the metadata the
		// returned resource reports, so that no further request is needed
		return head(key).map(head -> S3ObjectResource.builder()
			.s3Client(this.s3Client)
			.bucket(this.bucket)
			.key(key)
			.contentLength(head.contentLength())
			.lastModified(timestamp(head.lastModified()))
			.build());
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		String key = objectKey(location.requireArtifactPath());
		String directoryPrefix = key + "/";
		List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH_SIZE);
		boolean deleted = false;
		try {
			// A single listing covers both the object itself and, when the location
			// denotes a directory, every object underneath it
			for (ListObjectsV2Response page : listPages(ListObjectsV2Request.builder()
				.bucket(this.bucket)
				.prefix(key)
				.maxKeys(DELETE_BATCH_SIZE)
				.build())) {
				for (S3Object object : page.contents()) {
					// "org/example/lib" must not match "org/example/library.jar"
					if (object.key().equals(key) || object.key().startsWith(directoryPrefix)) {
						batch.add(ObjectIdentifier.builder().key(object.key()).build());
					}
				}
				deleted |= deleteBatch(batch);
			}
		}
		catch (SdkException e) {
			throw new IOException("Failed to delete s3://%s/%s".formatted(this.bucket, key), e);
		}
		return deleted;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		String prefix = directoryPrefix(location);
		List<StorageEntry> entries = new ArrayList<>();
		try {
			for (ListObjectsV2Response page : listPages(
					ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix).delimiter("/").build())) {
				for (CommonPrefix commonPrefix : page.commonPrefixes()) {
					String childPrefix = commonPrefix.prefix();
					String name = childPrefix.substring(prefix.length(), childPrefix.length() - 1);
					entries.add(StorageEntry.builder()
						.name(name)
						.type(StorageEntryType.DIRECTORY)
						.path(location.resolve(name).artifactPath())
						.build());
				}
				for (S3Object object : page.contents()) {
					String name = object.key().substring(prefix.length());
					if (name.isEmpty()) {
						// A directory marker object created by another tool
						continue;
					}
					entries.add(StorageEntry.builder()
						.name(name)
						.type(StorageEntryType.FILE)
						.path(location.resolve(name).artifactPath())
						.size(object.size())
						.lastModified(timestamp(object.lastModified()))
						.build());
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
		if (!location.isRoot()) {
			Optional<StorageEntry> file = head(objectKey(location)).map(head -> StorageEntry.builder()
				.name(location.name())
				.type(StorageEntryType.FILE)
				.path(location.artifactPath())
				.size(head.contentLength())
				.lastModified(timestamp(head.lastModified()))
				.build());
			if (file.isPresent()) {
				return file;
			}
		}
		if (!hasObjectsUnder(directoryPrefix(location))) {
			return Optional.empty();
		}
		// A directory exists as long as an object lives under its prefix; object storage
		// keeps no timestamp for it
		return Optional.of(StorageEntry.builder()
			.name(location.name())
			.type(StorageEntryType.DIRECTORY)
			.path(location.artifactPath())
			.build());
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		String prefix = directoryPrefix(ArtifactLocation.root(repositoryId));
		StorageStats.Builder builder = StorageStats.builder();
		try {
			for (ListObjectsV2Response page : listPages(
					ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix).build())) {
				for (S3Object object : page.contents()) {
					String key = object.key();
					builder.addFile(key.substring(key.lastIndexOf('/') + 1), object.size(),
							timestamp(object.lastModified()));
				}
			}
		}
		catch (SdkException e) {
			throw new IOException("Failed to list s3://%s/%s".formatted(this.bucket, prefix), e);
		}
		return builder.build();
	}

	/**
	 * The modification timestamp of an object, truncated to the second granularity a
	 * listing reports, so that the same object carries the same timestamp whether it was
	 * described by {@code HeadObject} or by {@code ListObjectsV2}.
	 */
	private static Instant timestamp(Instant lastModified) {
		return lastModified.truncatedTo(ChronoUnit.SECONDS);
	}

	private Optional<HeadObjectResponse> head(String key) {
		try {
			return Optional
				.of(this.s3Client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build()));
		}
		catch (AwsServiceException e) {
			// A HeadObject response carries no body to parse an error code from, so a
			// missing object can surface as a bare 404 instead of NoSuchKey
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw e;
		}
	}

	private boolean hasObjectsUnder(String prefix) throws IOException {
		try {
			return !this.s3Client
				.listObjectsV2(ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix).maxKeys(1).build())
				.contents()
				.isEmpty();
		}
		catch (SdkException e) {
			throw new IOException("Failed to list s3://%s/%s".formatted(this.bucket, prefix), e);
		}
	}

	private Iterable<ListObjectsV2Response> listPages(ListObjectsV2Request request) {
		return this.s3Client.listObjectsV2Paginator(request);
	}

	private boolean deleteBatch(List<ObjectIdentifier> batch) throws IOException {
		if (batch.isEmpty()) {
			return false;
		}
		DeleteObjectsResponse response = this.s3Client.deleteObjects(DeleteObjectsRequest.builder()
			.bucket(this.bucket)
			.delete(Delete.builder().objects(batch).build())
			.build());
		batch.clear();
		// A batched delete reports the keys it could not remove instead of failing
		if (!response.errors().isEmpty()) {
			throw new IOException("Failed to delete %d object(s) from s3://%s, starting with '%s': %s".formatted(
					response.errors().size(), this.bucket, response.errors().getFirst().key(),
					response.errors().getFirst().message()));
		}
		return true;
	}

	/**
	 * The key of the object stored at the given location.
	 */
	private String objectKey(ArtifactLocation location) {
		return this.keyPrefix + location.repositoryId() + "/" + location.artifactPath();
	}

	/**
	 * The key prefix every object under the given directory shares, trailing slash
	 * included.
	 */
	private String directoryPrefix(ArtifactLocation location) {
		String repositoryPrefix = this.keyPrefix + location.repositoryId() + "/";
		return location.isRoot() ? repositoryPrefix : repositoryPrefix + location.artifactPath() + "/";
	}

	private static String normalizeKeyPrefix(String keyPrefix) {
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

		private String keyPrefix = "";

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

		public Builder bucket(String bucket) {
			this.bucket = bucket;
			return this;
		}

		public Builder keyPrefix(String keyPrefix) {
			this.keyPrefix = keyPrefix;
			return this;
		}

		public S3StorageService build() {
			return new S3StorageService(Objects.requireNonNull(this.s3Client, "s3Client is required"),
					Objects.requireNonNull(this.outputStreamProvider, "outputStreamProvider is required"),
					Objects.requireNonNull(this.bucket, "bucket is required"), this.keyPrefix);
		}

	}

}
