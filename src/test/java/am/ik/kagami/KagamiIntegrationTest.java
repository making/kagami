package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.repositories.mock.is-private=true", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass", "spring.http.client.redirects=dont_follow" })
@Import(MockConfig.class)
public class KagamiIntegrationTest {

	RestClient restClient;

	@Autowired
	MockServer mockServer;

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder, @LocalServerPort int port) {
		this.restClient = restClientBuilder.baseUrl("http://localhost:" + port)
			.defaultStatusHandler(__ -> true, (req, res) -> {
			})
			.build();
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
		String cookie = loginFormResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
		ResponseEntity<String> loginResponse = this.restClient.post()
			.uri("/login")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("username=test&password=pass&_csrf=" + csrfToken)
			.header(HttpHeaders.COOKIE, cookie)
			.retrieve()
			.toEntity(String.class);
		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		cookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
		ResponseEntity<String> tokenResponse = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=" + String.join(",", repositories) + "&scope=" + String.join(",", scope))
			.header(HttpHeaders.COOKIE, cookie)
			.retrieve()
			.toEntity(String.class);
		assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		return tokenResponse.getBody();
	}

	@Test
	void getArtifactsShouldBeUnAuthorizedWithoutToken() {
		var response = this.restClient.get()
			.uri("/artifacts/mock/junit/junit/4.13.2/junit-4.13.2.pom")
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders()).containsEntry(HttpHeaders.WWW_AUTHENTICATE, List.of("Bearer"));
	}

	@Test
	void getArtifactsShouldBeOkWithBearerToken() {
		this.mockServer.GET("/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));
		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void getArtifactsShouldBeOkWithBasicAuth() {
		this.mockServer.GET("/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));
		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void getArtifactsShouldBeUnAuthorizedWithoutValidRepositories() {
		String token = issueToken(List.of("another"), List.of("artifacts:read"));
		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders()).hasEntrySatisfying(HttpHeaders.WWW_AUTHENTICATE, values -> {
			assertThat(values).hasSize(1);
			assertThat(values.getFirst())
				.contains("Token does not contain the repository 'mock' in 'kagami:repositories' claim");
		});
	}

	@Test
	void getArtifactsShouldBeForbiddenWithoutValidScope() {
		String token = issueToken(List.of("mock"), List.of("artifacts:delete"));
		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders()).hasEntrySatisfying(HttpHeaders.WWW_AUTHENTICATE, values -> {
			assertThat(values).hasSize(1);
			assertThat(values.getFirst())
				.contains("The request requires higher privileges than provided by the access token.");
		});
	}

	@Test
	void deleteArtifactsShouldBeUnAuthorizedWithoutToken() {
		var response = this.restClient.delete()
			.uri("/artifacts/mock/junit/junit/4.13.2/junit-4.13.2.pom")
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders()).containsEntry(HttpHeaders.WWW_AUTHENTICATE, List.of("Bearer"));
	}

	@Test
	void deleteArtifactsShouldBeNoContentWithBasicAuth() {
		this.mockServer.GET("/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));
		String token = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));
		this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		var response = this.restClient.delete()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void deleteArtifactsShouldBeUnAuthorizedWithoutValidRepositories() {
		String token = issueToken(List.of("another"), List.of("artifacts:read", "artifacts:delete"));
		var response = this.restClient.delete()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders()).hasEntrySatisfying(HttpHeaders.WWW_AUTHENTICATE, values -> {
			assertThat(values).hasSize(1);
			assertThat(values.getFirst())
				.contains("Token does not contain the repository 'mock' in 'kagami:repositories' claim");
		});
	}

	@Test
	void deleteArtifactsShouldBeForbiddenWithoutValidScope() {
		String token = issueToken(List.of("mock"), List.of("artifacts:read"));
		var response = this.restClient.delete()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders()).hasEntrySatisfying(HttpHeaders.WWW_AUTHENTICATE, values -> {
			assertThat(values).hasSize(1);
			assertThat(values.getFirst())
				.contains("The request requires higher privileges than provided by the access token.");
		});
	}

}
