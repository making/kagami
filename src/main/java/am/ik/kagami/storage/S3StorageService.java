package am.ik.kagami.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Amazon S3 (and S3-compatible object storage) implementation of {@link StorageService}.
 * <p>
 * Keys are structured as {@code {keyPrefix}{repositoryId}/{artifactPath}} where the
 * optional key prefix comes from {@code kagami.storage.s3.key-prefix}. Endpoint, region
 * and credentials are resolved from the Spring Cloud AWS properties
 * ({@code spring.cloud.aws.*}) when the {@link S3Client} is built.
 */
public class S3StorageService implements StorageService {

	private static final int DELETE_BATCH_SIZE = 1000;

	private final S3Client s3Client;

	private final String bucket;

	private final @Nullable String keyPrefix;

	public S3StorageService(S3Client s3Client, String bucket, @Nullable String keyPrefix) {
		this.s3Client = s3Client;
		this.bucket = bucket;
		this.keyPrefix = keyPrefix;
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		String key = key(location.requireArtifactPath());
		// Buffer to a temporary file so that the object is uploaded with a known content
		// length; unknown-length streaming uploads cannot be replayed by the SDK on a
		// retry
		Path temporaryFile = Files.createTempFile("kagami-s3-upload-", ".tmp");
		try {
			Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
			PutObjectRequest request = PutObjectRequest.builder()
				.bucket(this.bucket)
				.key(key)
				.contentType(contentType(location.artifactPath()))
				.build();
			this.s3Client.putObject(request, RequestBody.fromFile(temporaryFile));
		}
		finally {
			Files.deleteIfExists(temporaryFile);
		}
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		String key = key(location.requireArtifactPath());
		try {
			var head = this.s3Client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return Optional.of(new S3ObjectResource(this.s3Client, this.bucket, key, location.name(),
					head.contentLength(), truncateToSeconds(head.lastModified())));
		}
		catch (NoSuchKeyException e) {
			return Optional.empty();
		}
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		String key = key(location.requireArtifactPath());
		if (objectExists(key)) {
			this.s3Client.deleteObject(builder -> builder.bucket(this.bucket).key(key));
			return true;
		}
		List<String> keys = keysUnderPrefix(directoryPrefix(location));
		if (keys.isEmpty()) {
			return false;
		}
		deleteBatched(keys);
		return true;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		String prefix = location.isRoot() ? repositoryPrefix(location.repositoryId()) : directoryPrefix(location);
		Map<String, StorageEntry> entries = new TreeMap<>();
		forEachPage(builder -> builder.prefix(prefix).delimiter("/"), response -> {
			for (CommonPrefix commonPrefix : response.commonPrefixes()) {
				String name = nameAfterPrefix(commonPrefix.prefix(), prefix);
				entries.put(name, directoryEntry(location, name));
			}
			for (S3Object object : response.contents()) {
				if (object.key().equals(prefix)) {
					// The object that denotes the directory itself
					continue;
				}
				String name = nameAfterPrefix(object.key(), prefix);
				entries.put(name,
						StorageEntry.builder()
							.name(name)
							.type(StorageEntryType.FILE)
							.path(location.resolve(name).artifactPath())
							.size(object.size())
							.lastModified(truncateToSeconds(object.lastModified()))
							.build());
			}
		});
		return List.copyOf(entries.values());
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		if (location.isRoot()) {
			return statRoot(location);
		}
		String key = key(location);
		if (objectExists(key)) {
			var head = this.s3Client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return Optional.of(StorageEntry.builder()
				.name(location.name())
				.type(StorageEntryType.FILE)
				.path(location.artifactPath())
				.size(head.contentLength())
				.lastModified(truncateToSeconds(head.lastModified()))
				.build());
		}
		if (keysUnderPrefix(directoryPrefix(location)).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(StorageEntry.builder()
			.name(location.name())
			.type(StorageEntryType.DIRECTORY)
			.path(location.artifactPath())
			.build());
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		StorageStats.Builder builder = StorageStats.builder();
		forEachPage(request -> request.prefix(repositoryPrefix(repositoryId)), response -> {
			for (S3Object object : response.contents()) {
				String key = object.key();
				builder.addFile(fileName(key), object.size(), truncateToSeconds(object.lastModified()));
			}
		});
		return builder.build();
	}

	private Optional<StorageEntry> statRoot(ArtifactLocation location) {
		List<S3Object> contents = new ArrayList<>();
		forEachPage(builder -> builder.prefix(repositoryPrefix(location.repositoryId())).maxKeys(1),
				response -> contents.addAll(response.contents()));
		if (contents.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(StorageEntry.builder()
			.name(location.name())
			.type(StorageEntryType.DIRECTORY)
			.path(location.artifactPath())
			.build());
	}

	private void forEachPage(Consumer<ListObjectsV2Request.Builder> requestConfigurer,
			Consumer<ListObjectsV2Response> responseConsumer) {
		this.s3Client
			.listObjectsV2Paginator(
					ListObjectsV2Request.builder().bucket(this.bucket).applyMutation(requestConfigurer).build())
			.forEach(responseConsumer);
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

	private List<String> keysUnderPrefix(String prefix) {
		List<String> keys = new ArrayList<>();
		forEachPage(builder -> builder.prefix(prefix), response -> {
			for (S3Object object : response.contents()) {
				keys.add(object.key());
			}
		});
		return keys;
	}

	private void deleteBatched(List<String> keys) throws IOException {
		for (int fromIndex = 0; fromIndex < keys.size(); fromIndex += DELETE_BATCH_SIZE) {
			List<ObjectIdentifier> identifiers = keys
				.subList(fromIndex, Math.min(fromIndex + DELETE_BATCH_SIZE, keys.size()))
				.stream()
				.map(key -> ObjectIdentifier.builder().key(key).build())
				.toList();
			this.s3Client.deleteObjects(DeleteObjectsRequest.builder()
				.bucket(this.bucket)
				.delete(Delete.builder().objects(identifiers).build())
				.build());
		}
	}

	private static StorageEntry directoryEntry(ArtifactLocation location, String name) {
		return StorageEntry.builder()
			.name(name)
			.type(StorageEntryType.DIRECTORY)
			.path(location.resolve(name).artifactPath())
			.build();
	}

	private String key(ArtifactLocation location) {
		return repositoryPrefix(location.repositoryId()) + location.artifactPath();
	}

	private String repositoryPrefix(String repositoryId) {
		return prefix() + repositoryId + "/";
	}

	private String directoryPrefix(ArtifactLocation location) {
		return key(location) + "/";
	}

	private String prefix() {
		if (this.keyPrefix == null || this.keyPrefix.isEmpty()) {
			return "";
		}
		return this.keyPrefix.endsWith("/") ? this.keyPrefix : this.keyPrefix + "/";
	}

	private static String fileName(String key) {
		int lastSlash = key.lastIndexOf('/');
		return lastSlash < 0 ? key : key.substring(lastSlash + 1);
	}

	/**
	 * Truncates a timestamp to seconds. Object storage exposes second-level precision for
	 * object listings, so timestamps are truncated everywhere to keep them consistent
	 * between a {@code HeadObject} and a {@code ListObjectsV2}.
	 */
	private static Instant truncateToSeconds(Instant instant) {
		return instant.truncatedTo(ChronoUnit.SECONDS);
	}

	private static String nameAfterPrefix(String key, String prefix) {
		String name = key.substring(prefix.length());
		while (name.endsWith("/")) {
			name = name.substring(0, name.length() - 1);
		}
		return name;
	}

	private static String contentType(String artifactPath) {
		if (artifactPath.endsWith(".jar")) {
			return "application/java-archive";
		}
		else if (artifactPath.endsWith(".pom") || artifactPath.endsWith(".xml")) {
			return MediaType.APPLICATION_XML_VALUE;
		}
		else if (artifactPath.endsWith(".sha1") || artifactPath.endsWith(".md5") || artifactPath.endsWith(".sha256")
				|| artifactPath.endsWith(".sha512")) {
			return MediaType.TEXT_PLAIN_VALUE;
		}
		else if (artifactPath.endsWith(".asc")) {
			return "application/pgp-signature";
		}
		else {
			return MediaType.APPLICATION_OCTET_STREAM_VALUE;
		}
	}

}
