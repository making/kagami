package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test of the artifact endpoints against the S3 storage backend. An artifact
 * fetched from the {@link MockServer} remote must land in the bucket, be served from
 * storage on a second request, and be removed by a {@code DELETE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.repositories.mock.is-private=true", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass", "spring.http.clients.redirects=dont_follow" })
@Import(MockConfig.class)
class S3IntegrationTest {

	static final String POM_PATH = "am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom";

	static final String BUCKET = "kagami-integration";

	static final RustfsContainer container = new RustfsContainer();

	@Autowired
	MockServer mockServer;

	RestClient restClient;

	@LocalServerPort
	int port;

	@AfterAll
	static void stopContainer() {
		container.close();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		// Start the container when the properties are registered, which happens before
		// the
		// Spring context is created
		if (!container.isRunning()) {
			container.start();
			container.createBucket(BUCKET);
		}
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("spring.cloud.aws.s3.enabled", () -> "true");
		registry.add("spring.cloud.aws.s3.endpoint", () -> container.endpoint());
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
		registry.add("spring.cloud.aws.region.static", () -> RustfsContainer.REGION);
		registry.add("spring.cloud.aws.credentials.access-key", () -> RustfsContainer.ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> RustfsContainer.SECRET_KEY);
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder) {
		this.restClient = restClientBuilder.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler(__ -> true, (req, res) -> {
			})
			.build();
	}

	@AfterEach
	void emptyBucket() {
		container.deleteAllObjects(BUCKET);
	}

	@Test
	void artifactShouldBeMirroredToS3AndServedFromStorage() {
		this.mockServer.GET("/" + POM_PATH, req -> Response.ok("<project></project>"))
			.GET("/" + POM_PATH + ".sha1", req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));

		// First request fetches from the remote and stores into S3
		ResponseEntity<Void> first = this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

		// The object landed in the bucket
		try (var client = container.client()) {
			var headObject = client.headObject(request -> request.bucket(BUCKET).key("mock/" + POM_PATH));
			assertThat(headObject.contentLength()).isEqualTo("<project></project>".length());
		}

		// Second request is served from storage
		ResponseEntity<Void> second = this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

		// DELETE removes it
		String deleteToken = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));
		ResponseEntity<Void> delete = this.restClient.delete()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(deleteToken)))
			.retrieve()
			.toBodilessEntity();
		assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		// The object is gone from the bucket
		try (var client = container.client()) {
			assertThatThrownBy(() -> client.headObject(request -> request.bucket(BUCKET).key("mock/" + POM_PATH)))
				.isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
		}
	}

	String issueToken(List<String> repositories, List<String> scope) {
		ResponseEntity<String> loginFormResponse = this.restClient.get()
			.uri("/login")
			.retrieve()
			.toEntity(String.class);
		assertThat(loginFormResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(loginFormResponse.getBody()).isNotNull();
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"")
			.matcher(loginFormResponse.getBody());
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

}
