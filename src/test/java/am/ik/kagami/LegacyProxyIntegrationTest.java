package am.ik.kagami;

import am.ik.kagami.mockserver.MockProxyServer;
import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for a legacy environment where every outgoing connection has to go
 * through an HTTP proxy that requires Basic authentication.
 * <p>
 * The {@code mock} repository is reached through the proxy while the {@code direct}
 * repository, which points at the very same server via a host listed in
 * {@code kagami.proxy.non-proxy-hosts}, is reached directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.proxy.non-proxy-hosts=localhost", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass" })
@Import({ MockConfig.class, LegacyProxyIntegrationTest.LegacyProxyConfig.class })
class LegacyProxyIntegrationTest {

	static final String PROXY_USERNAME = "proxyuser";

	static final String PROXY_PASSWORD = "proxypass";

	RestClient restClient;

	@Autowired
	MockServer mockServer;

	@Autowired
	MockProxyServer mockProxyServer;

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder, @LocalServerPort int port) {
		// The client talks to Kagami over "localhost", which is excluded from proxying
		this.restClient = restClientBuilder.baseUrl("http://localhost:" + port)
			.defaultStatusHandler(__ -> true, (req, res) -> {
			})
			.build();
	}

	@Test
	void artifactShouldBeFetchedThroughTheProxyByMavenResolver() {
		this.mockServer.GET("/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));

		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("<project></project>");
		assertThat(this.mockProxyServer.forwardedRequests())
			.contains("GET " + proxiedRepositoryUrl() + "/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom");
		// The proxy challenged the client with "407 Proxy Authentication Required" and
		// the request succeeded once the credentials were sent
		assertThat(this.mockProxyServer.authenticationChallenges()).isPositive();
	}

	@Test
	void metadataShouldBeFetchedThroughTheProxyByRestClient() {
		this.mockServer.GET("/am/ik/kagami/kagami/maven-metadata.xml",
				req -> Response.ok("<metadata></metadata>", "text/xml"));

		var response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/maven-metadata.xml")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("<metadata></metadata>");
		assertThat(this.mockProxyServer.forwardedRequests())
			.contains("GET " + proxiedRepositoryUrl() + "/am/ik/kagami/kagami/maven-metadata.xml");
	}

	@Test
	void artifactShouldBeFetchedDirectlyWhenTheHostIsExcludedFromProxying() {
		this.mockServer.GET("/am/ik/kagami/kagami/2.0.0/kagami-2.0.0.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/2.0.0/kagami-2.0.0.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));

		var response = this.restClient.get()
			.uri("/artifacts/direct/am/ik/kagami/kagami/2.0.0/kagami-2.0.0.pom")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("<project></project>");
		assertThat(this.mockProxyServer.forwardedRequests())
			.noneMatch(request -> request.contains(directRepositoryUrl()));
	}

	String proxiedRepositoryUrl() {
		return "http://127.0.0.1:%d".formatted(this.mockServer.port());
	}

	String directRepositoryUrl() {
		return "http://localhost:%d".formatted(this.mockServer.port());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LegacyProxyConfig {

		@Bean
		MockProxyServer mockProxyServer() {
			MockProxyServer mockProxyServer = MockProxyServer.withBasicAuthentication(PROXY_USERNAME, PROXY_PASSWORD);
			mockProxyServer.run();
			return mockProxyServer;
		}

		@Bean
		DynamicPropertyRegistrar legacyProxyDynamicPropertyRegistrar(MockProxyServer mockProxyServer,
				MockServer mockServer) {
			return registry -> {
				registry.add("kagami.proxy.url", () -> "http://%s:%s@127.0.0.1:%d".formatted(PROXY_USERNAME,
						PROXY_PASSWORD, mockProxyServer.port()));
				// The same server as the "mock" repository, reached through a host that
				// is excluded from proxying
				registry.add("kagami.repositories.direct.url",
						() -> "http://localhost:%d".formatted(mockServer.port()));
			};
		}

	}

}
