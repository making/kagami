package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer.Response;
import am.ik.kagami.storage.Rustfs;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs the full application against the S3 storage backend on rustfs.
 */
class S3KagamiIntegrationTest extends KagamiIntegrationTest {

	static final GenericContainer<?> RUSTFS = Rustfs.container();

	static final String BUCKET = "kagami-it-" + UUID.randomUUID();

	static final S3Client S3_CLIENT;

	static {
		RUSTFS.start();
		S3_CLIENT = Rustfs.client(RUSTFS);
		Rustfs.createBucket(S3_CLIENT, BUCKET);
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		configureStorageProperties(registry, () -> "/tmp/kagami-storage-unused-for-s3");
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
		registry.add("spring.cloud.aws.credentials.access-key", () -> Rustfs.ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> Rustfs.SECRET_KEY);
		registry.add("spring.cloud.aws.s3.endpoint", () -> Rustfs.endpoint(RUSTFS));
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
	}

	@Test
	void fetchedArtifactLandsInBucketAndIsServedFromStorageAfterwards() {
		String artifactPath = "/am/ik/kagami/kagami/0.0.3/kagami-0.0.3.pom";
		String key = "mock" + artifactPath;
		AtomicBoolean failRemote = new AtomicBoolean(false);
		this.mockServer.GET(artifactPath,
				req -> failRemote.get() ? Response.builder().status(500).build() : Response.ok("<project></project>"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));

		// The first fetch pulls the artifact from the remote and stores it in the bucket
		ResponseEntity<Void> first = this.restClient.get()
			.uri("/artifacts/mock" + artifactPath)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThatCode(() -> S3_CLIENT.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build()))
			.doesNotThrowAnyException();

		// Once the remote is down, the stored object still serves the artifact
		failRemote.set(true);
		ResponseEntity<Void> second = this.restClient.get()
			.uri("/artifacts/mock" + artifactPath)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

		// DELETE removes the object from the bucket as well
		ResponseEntity<Void> delete = this.restClient.delete()
			.uri("/artifacts/mock" + artifactPath)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThatCode(() -> S3_CLIENT.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build()))
			.isInstanceOf(NoSuchKeyException.class);
	}

}
