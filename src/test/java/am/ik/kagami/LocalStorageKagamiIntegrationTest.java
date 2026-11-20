package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.repository.RepositoryRetentionService;
import am.ik.kagami.repository.RepositoryRetentionService.RetentionResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the artifact API integration tests against the local file system storage
 */
class LocalStorageKagamiIntegrationTest extends KagamiIntegrationTestBase {

	@TempDir
	static Path tempDir;

	@Autowired
	RepositoryRetentionService retentionService;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		// The smallest limit any stored artifact exceeds; the test applies the policy
		// explicitly instead of waiting for the sweep
		registry.add("kagami.repositories.mock.retention.max-size", () -> "1B");
		registry.add("kagami.retention.initial-delay", () -> "PT24H");
	}

	@Test
	void evictedArtifactIsRefetchedFromTheUpstream() throws Exception {
		AtomicInteger remoteHits = mockRemoteArtifact("0.0.9");
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));
		String uri = "/artifacts/mock/am/ik/kagami/kagami/0.0.9/kagami-0.0.9.pom";

		ResponseEntity<Void> first = this.restClient.get()
			.uri(uri)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(remoteHits).hasValue(1);

		RetentionResult result = this.retentionService.apply("mock");
		assertThat(result.evictedPaths()).contains("am/ik/kagami/kagami/0.0.9/kagami-0.0.9.pom");

		ResponseEntity<Void> refetched = this.restClient.get()
			.uri(uri)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(refetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(remoteHits).hasValue(2);
	}

	@Test
	void getMavenMetadataReturnsBadGatewayWhenTheUpstreamFailsWithoutACachedCopy() {
		String path = "/am/ik/kagami/kagami/9.9.9/maven-metadata.xml";
		this.mockServer.GET(path, request -> MockServer.Response.builder().status(503).body("").build());
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));

		ResponseEntity<Void> response = this.restClient.get()
			.uri("/artifacts/mock" + path)
			.headers(headers -> headers.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

}
