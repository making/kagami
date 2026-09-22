package am.ik.kagami.storage;

import am.ik.kagami.RustFsContainer;
import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import io.awspring.cloud.s3.PropertiesS3ObjectContentTypeResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Runs the storage contract against S3-compatible object storage, plus the S3-specific
 * key layout. Every test gets a fresh bucket.
 */
class S3StorageServiceTest extends StorageServiceContractTest {

	static final RustFsContainer rustFs = RustFsContainer.shared();

	static S3Client s3Client;

	String bucket;

	@BeforeAll
	static void createClient() {
		s3Client = rustFs.newS3Client();
	}

	@AfterAll
	static void closeClient() {
		s3Client.close();
	}

	@BeforeEach
	void createBucket() {
		this.bucket = "kagami-" + UUID.randomUUID();
		s3Client.createBucket(request -> request.bucket(this.bucket));
	}

	@Override
	protected StorageService storageService() {
		return storageService("mirror");
	}

	S3StorageService storageService(@Nullable String keyPrefix) {
		return S3StorageService.builder()
			.s3Client(s3Client)
			.outputStreamProvider(outputStreamProvider())
			.bucket(this.bucket)
			.keyPrefix(keyPrefix)
			.build();
	}

	@Test
	void deleteStopsAtTheDirectoryBoundary() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar");
		store(storage, location("org/example/library.jar"), "sibling");

		assertThat(storage.delete(location("org/example/lib"))).isTrue();
		assertThat(storage.retrieve(location("org/example/lib/1.0/lib-1.0.jar"))).isEmpty();
		// "org/example/lib" is a key prefix of "org/example/library.jar" without being
		// its directory
		assertThat(storage.retrieve(location("org/example/library.jar"))).isPresent();
	}

	@Test
	void storeWritesObjectUnderKeyPrefixWithContentType() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content");
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom");
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha1"), "sha1");

		assertThat(head("mirror/test-repo/org/example/lib/1.0/lib-1.0.jar").contentType())
			.isEqualTo("application/java-archive");
		assertThat(head("mirror/test-repo/org/example/lib/1.0/lib-1.0.pom").contentType()).isEqualTo("application/xml");
		assertThat(head("mirror/test-repo/org/example/lib/1.0/lib-1.0.jar.sha1").contentType()).isEqualTo("text/plain");
	}

	@Test
	void keyPrefixIsOptionalAndSlashesAreNormalized() throws IOException {
		store(storageService(null), location("org/example/lib.jar"), "no prefix");
		store(storageService("/nested/prefix/"), location("org/example/lib.jar"), "nested prefix");

		assertThat(head("test-repo/org/example/lib.jar").contentLength()).isEqualTo(9L);
		assertThat(head("nested/prefix/test-repo/org/example/lib.jar").contentLength()).isEqualTo(13L);
		assertThat(s3Client.listObjectsV2(request -> request.bucket(this.bucket)).contents()).extracting(S3Object::key)
			.containsExactlyInAnyOrder("test-repo/org/example/lib.jar", "nested/prefix/test-repo/org/example/lib.jar");
	}

	@Test
	void storeContentLargerThanTheBufferUsesMultipartUpload() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/big/1.0/big-1.0.jar");
		// Larger than the 5 MB in-memory buffer so that the multipart path is exercised
		byte[] content = new byte[6 * 1024 * 1024 + 1];
		new Random(42).nextBytes(content);
		try (InputStream inputStream = new ByteArrayInputStream(content)) {
			storage.store(location, inputStream);
		}

		Resource resource = storage.retrieve(location).orElseThrow();
		assertThat(resource.contentLength()).isEqualTo(content.length);
		try (InputStream inputStream = resource.getInputStream()) {
			assertThat(inputStream.readAllBytes()).isEqualTo(content);
		}
		assertThat(storage.stat(location).orElseThrow().size()).isEqualTo((long) content.length);
	}

	@Test
	void storeAbortsWhenTheInputFails() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/broken/1.0/broken-1.0.jar");
		InputStream failing = new InputStream() {
			private int remaining = 1024;

			@Override
			public int read() throws IOException {
				if (this.remaining-- > 0) {
					return 'x';
				}
				throw new IOException("connection reset");
			}
		};

		assertThatExceptionOfType(IOException.class).isThrownBy(() -> storage.store(location, failing))
			.withMessage("connection reset");
		assertThat(storage.retrieve(location)).isEmpty();
	}

	@Test
	void retrievedResourceAnswersMetadataWithoutFurtherRequests() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.jar");
		store(storage, location, "jar content");
		Resource resource = storage.retrieve(location).orElseThrow();

		// Delete behind the resource's back: cached metadata still answers
		s3Client
			.deleteObject(request -> request.bucket(this.bucket).key("mirror/test-repo/" + location.artifactPath()));
		assertThat(resource.exists()).isTrue();
		assertThat(resource.isReadable()).isTrue();
		assertThat(resource.contentLength()).isEqualTo(11L);
		assertThat(resource.lastModified()).isPositive();
		assertThat(resource.getDescription())
			.isEqualTo("S3 object [s3://%s/mirror/test-repo/%s]".formatted(this.bucket, location.artifactPath()));
		assertThatExceptionOfType(IOException.class).isThrownBy(resource::getInputStream);
	}

	@Test
	void bucketIsRequired() {
		assertThatIllegalStateException()
			.isThrownBy(() -> S3StorageService.builder()
				.s3Client(s3Client)
				.outputStreamProvider(outputStreamProvider())
				.bucket(" ")
				.build())
			.withMessageContaining("kagami.storage.s3.bucket");
	}

	/**
	 * The upload strategy Spring Cloud AWS auto-configures, content type resolver
	 * included.
	 * @return the provider
	 */
	static InMemoryBufferingS3OutputStreamProvider outputStreamProvider() {
		return new InMemoryBufferingS3OutputStreamProvider(s3Client, new PropertiesS3ObjectContentTypeResolver());
	}

	HeadObjectResponse head(String key) {
		return s3Client.headObject(request -> request.bucket(this.bucket).key(key));
	}

}
