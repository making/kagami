package am.ik.kagami;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Runs the artifact API integration tests against S3-compatible object storage and checks
 * that the objects actually land in, and disappear from, the bucket.
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageKagamiIntegrationTest extends KagamiIntegrationTestBase {

	@Autowired
	S3Client s3Client;

	@Test
	void fetchedArtifactIsStoredInTheBucketAndDeleteRemovesIt() {
		AtomicInteger remoteHits = mockRemoteArtifact("0.0.7");
		String token = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));
		String uri = "/artifacts/mock/am/ik/kagami/kagami/0.0.7/kagami-0.0.7.pom";
		String key = "mock/am/ik/kagami/kagami/0.0.7/kagami-0.0.7.pom";

		ResponseEntity<String> fetched = this.restClient.get()
			.uri(uri)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toEntity(String.class);
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(remoteHits).hasValue(1);

		HeadObjectResponse object = this.s3Client
			.headObject(request -> request.bucket(RustFsContainer.BUCKET).key(key));
		assertThat(object.contentLength()).isEqualTo("<project></project>".length());
		assertThat(object.contentType()).isEqualTo("application/xml");

		ResponseEntity<Void> deleted = this.restClient.delete()
			.uri(uri)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThatExceptionOfType(NoSuchKeyException.class)
			.isThrownBy(() -> this.s3Client.headObject(request -> request.bucket(RustFsContainer.BUCKET).key(key)));
	}

}
