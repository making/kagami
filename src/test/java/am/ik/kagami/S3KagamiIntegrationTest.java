package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer.Response;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the artifact mirroring scenario against the S3 storage backend and verifies the
 * objects that land in the bucket.
 */
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3KagamiIntegrationTest extends KagamiIntegrationTest {

	private static final String ARTIFACT_PATH = "am/ik/kagami/kagami/0.0.3/kagami-0.0.3.pom";

	@Autowired
	S3Client s3Client;

	@DynamicPropertySource
	static void configureStorage(DynamicPropertyRegistry registry) {
		RustfsContainer.registerProperties(registry);
	}

	@BeforeEach
	void clearStorage() {
		RustfsContainer.clearBucket();
	}

	@Test
	void artifactIsMirroredIntoTheBucketAndServedFromStorage() {
		AtomicInteger remoteCalls = new AtomicInteger();
		this.mockServer.GET("/" + ARTIFACT_PATH, request -> {
			remoteCalls.incrementAndGet();
			return Response.ok("<project></project>");
		});
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));

		// The first request fetches from the remote repository and stores the object
		ResponseEntity<String> first = this.restClient.get()
			.uri("/artifacts/mock/" + ARTIFACT_PATH)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toEntity(String.class);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).isEqualTo("<project></project>");
		assertThat(objectKeys()).contains("mock/" + ARTIFACT_PATH);
		int callsAfterFirstRequest = remoteCalls.get();
		assertThat(callsAfterFirstRequest).isPositive();

		// The second request is served from the storage backend, not the remote
		ResponseEntity<String> second = this.restClient.get()
			.uri("/artifacts/mock/" + ARTIFACT_PATH)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toEntity(String.class);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody()).isEqualTo("<project></project>");
		assertThat(remoteCalls.get()).isEqualTo(callsAfterFirstRequest);
	}

	@Test
	void deleteRemovesTheObjectFromTheBucket() {
		this.mockServer.GET("/" + ARTIFACT_PATH, request -> Response.ok("<project></project>"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));
		this.restClient.get()
			.uri("/artifacts/mock/" + ARTIFACT_PATH)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(objectKeys()).contains("mock/" + ARTIFACT_PATH);

		ResponseEntity<Void> deleted = this.restClient.delete()
			.uri("/artifacts/mock/" + ARTIFACT_PATH)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();

		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(objectKeys()).doesNotContain("mock/" + ARTIFACT_PATH);
	}

	private List<String> objectKeys() {
		return this.s3Client.listObjectsV2(request -> request.bucket(RustfsContainer.BUCKET))
			.contents()
			.stream()
			.map(S3Object::key)
			.toList();
	}

}
