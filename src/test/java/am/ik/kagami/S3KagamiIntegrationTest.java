package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the artifact fetch and delete flow against the S3 storage backend.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.repositories.mock.is-private=true", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass", "spring.http.clients.redirects=dont_follow" })
@Import(MockConfig.class)
class S3KagamiIntegrationTest {

	private static final String POM_PATH = "com/example/s3lib/1.0/s3lib-1.0.pom";

	private static final String POM_CONTENT = "<project></project>";

	private static final String OBJECT_KEY = "integration/mock/" + POM_PATH;

	RestClient restClient;

	S3Client s3Client;

	@Autowired
	MockServer mockServer;

	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		S3TestSupport.registerS3Properties(registry, "integration");
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder, @LocalServerPort int port) {
		this.restClient = restClientBuilder.baseUrl("http://localhost:" + port)
			.defaultStatusHandler(__ -> true, (req, res) -> {
			})
			.build();
		this.s3Client = S3TestSupport.newClient();
	}

	String issueToken(List<String> repositories, List<String> scope) {
		ResponseEntity<String> loginFormResponse = this.restClient.get()
			.uri("/login")
			.retrieve()
			.toEntity(String.class);
		assertThat(loginFormResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(loginFormResponse.getBody()).isNotNull();
		String loginForm = loginFormResponse.getBody();
		Pattern pattern = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
		Matcher matcher = pattern.matcher(loginForm);
		if (!matcher.find()) {
			throw new IllegalStateException("CSRF token not found in the login form");
		}
		String csrfToken = matcher.group(1);
		assertThat(loginFormResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNotNull();
		String cookie = loginFormResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
		ResponseEntity<String> loginResponse = this.restClient.post()
			.uri("/login")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("username=test&password=pass&_csrf=" + csrfToken)
			.header(HttpHeaders.COOKIE, cookie)
			.retrieve()
			.toEntity(String.class);
		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNotNull();
		cookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
		ResponseEntity<String> tokenResponse = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=" + String.join(",", repositories) + "&scope=" + String.join(",", scope))
			.header(HttpHeaders.COOKIE, cookie)
			.retrieve()
			.toEntity(String.class);
		assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tokenResponse.getBody()).isNotNull();
		return tokenResponse.getBody();
	}

	@Test
	void getArtifactShouldMirrorIntoBucketAndServeSecondRequestFromStorage() {
		this.mockServer.GET("/com/example/s3lib/1.0/s3lib-1.0.pom", req -> Response.ok(POM_CONTENT))
			.GET("/com/example/s3lib/1.0/s3lib-1.0.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));

		ResponseEntity<String> response = this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toEntity(String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(POM_CONTENT);

		// The artifact lands in the bucket
		assertThat(this.s3Client
			.headObject(HeadObjectRequest.builder().bucket(S3TestSupport.BUCKET).key(OBJECT_KEY).build())
			.contentLength()).isPositive();

		// The second request is served from storage even when the remote is gone
		this.mockServer.reset();
		ResponseEntity<String> cached = this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toEntity(String.class);
		assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(cached.getBody()).isEqualTo(POM_CONTENT);
	}

	@Test
	void deleteArtifactShouldRemoveObjectFromBucket() {
		this.mockServer.GET("/com/example/s3lib/1.0/s3lib-1.0.pom", req -> Response.ok(POM_CONTENT))
			.GET("/com/example/s3lib/1.0/s3lib-1.0.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));
		this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();

		ResponseEntity<Void> response = this.restClient.delete()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		assertThatThrownBy(() -> this.s3Client
			.getObject(GetObjectRequest.builder().bucket(S3TestSupport.BUCKET).key(OBJECT_KEY).build()))
			.isInstanceOf(NoSuchKeyException.class);
	}

	@Test
	void diskspaceHealthAndMetricsAreDisabled() {
		ResponseEntity<String> health = this.restClient.get().uri("/actuator/health").retrieve().toEntity(String.class);
		assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(health.getBody()).doesNotContain("diskSpace");

		ResponseEntity<String> metrics = this.restClient.get()
			.uri("/actuator/prometheus")
			.retrieve()
			.toEntity(String.class);
		assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(metrics.getBody()).doesNotContain("disk_");
	}

}
