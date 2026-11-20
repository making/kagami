package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
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
