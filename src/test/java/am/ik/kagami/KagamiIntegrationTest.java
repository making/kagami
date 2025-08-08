package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.repositories.mock.is-private=true" })
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
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=mock&scope=artifacts:read")
			.retrieve()
			.body(String.class);
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
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=mock&scope=artifacts:read")
			.retrieve()
			.body(String.class);
		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom")
			.headers(httpHeaders -> httpHeaders.setBasicAuth("", Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void getArtifactsShouldBeUnAuthorizedWithoutValidRepositories() {
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=another&scope=artifacts:read")
			.retrieve()
			.body(String.class);
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
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=mock&scope=artifacts:delete")
			.retrieve()
			.body(String.class);
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
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=mock&scope=artifacts:read,artifacts:delete")
			.retrieve()
			.body(String.class);
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
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=another&scope=artifacts:read,artifacts:delete")
			.retrieve()
			.body(String.class);
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
		String token = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=mock&scope=artifacts:get")
			.retrieve()
			.body(String.class);
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
