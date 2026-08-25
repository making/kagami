package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.Testcontainers.exposeHostPorts;

/**
 * End-to-end tests for a legacy environment where every outgoing connection has to go
 * through a Squid proxy that requires Basic authentication.
 * <p>
 * The {@code mock} repository is served by a mock server running on the host, which the
 * proxy reaches through {@code host.testcontainers.internal}, so it can only be fetched
 * through the proxy. The {@code direct} repository points at the very same server through
 * {@code localhost}, a host listed in {@code kagami.proxy.non-proxy-hosts}, so it is
 * fetched without the proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.proxy.non-proxy-hosts=localhost", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass" })
@Testcontainers
class LegacyProxyIntegrationTest {

	static final String PROXY_USERNAME = "proxyuser";

	// The password is hashed in src/test/resources/squid/passwd
	static final String PROXY_PASSWORD = "proxypass";

	static final int SQUID_PORT = 3128;

	static final MockServer mockServer = startMockServer();

	@Container
	static final GenericContainer<?> squid = new GenericContainer<>("ubuntu/squid:6.6-24.04_edge")
		.withExposedPorts(SQUID_PORT)
		.withCopyFileToContainer(MountableFile.forClasspathResource("squid/squid.conf"), "/etc/squid/squid.conf")
		.withCopyFileToContainer(MountableFile.forClasspathResource("squid/passwd"), "/etc/squid/passwd")
		.waitingFor(Wait.forLogMessage(".*Accepting HTTP Socket connections.*", 1));

	@TempDir
	static Path tempDir;

	RestClient restClient;

	static MockServer startMockServer() {
		MockServer mockServer = new MockServer(TestSocketUtils.findAvailableTcpPort());
		mockServer.run();
		// Make the mock server reachable from the proxy container as
		// "host.testcontainers.internal"
		exposeHostPorts(mockServer.port());
		return mockServer;
	}

	@AfterAll
	static void stopMockServer() {
		mockServer.close();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		registry.add("kagami.proxy.url", () -> "http://%s:%s@%s:%d".formatted(PROXY_USERNAME, PROXY_PASSWORD,
				squid.getHost(), squid.getMappedPort(SQUID_PORT)));
		registry.add("kagami.repositories.mock.url", LegacyProxyIntegrationTest::proxiedRepositoryUrl);
		registry.add("kagami.repositories.direct.url", LegacyProxyIntegrationTest::directRepositoryUrl);
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
		mockServer.GET("/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));

		ResponseEntity<String> response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("<project></project>");
		awaitAccessLogEntry("TCP_MISS/200", "GET %s/am/ik/kagami/kagami/1.0.0/kagami-1.0.0.pom %s"
			.formatted(proxiedRepositoryUrl(), PROXY_USERNAME));
		// The proxy challenged the client with "407 Proxy Authentication Required" and
		// the request succeeded once the credentials were sent
		assertThat(accessLog()).contains("TCP_DENIED/407");
	}

	@Test
	void metadataShouldBeFetchedThroughTheProxyByRestClient() {
		mockServer.GET("/am/ik/kagami/kagami/maven-metadata.xml",
				req -> Response.ok("<metadata></metadata>", "text/xml"));

		ResponseEntity<String> response = this.restClient.get()
			.uri("/artifacts/mock/am/ik/kagami/kagami/maven-metadata.xml")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("<metadata></metadata>");
		awaitAccessLogEntry("TCP_MISS/200",
				"GET %s/am/ik/kagami/kagami/maven-metadata.xml %s".formatted(proxiedRepositoryUrl(), PROXY_USERNAME));
	}

	@Test
	void artifactShouldBeFetchedDirectlyWhenTheHostIsExcludedFromProxying() {
		mockServer.GET("/am/ik/kagami/kagami/2.0.0/kagami-2.0.0.pom", req -> Response.ok("<project></project>"))
			.GET("/am/ik/kagami/kagami/2.0.0/kagami-2.0.0.pom.sha1",
					req -> Response.ok("147ddc4bbee044878ea3f8341a40e770e4b92f4e"));

		ResponseEntity<String> response = this.restClient.get()
			.uri("/artifacts/direct/am/ik/kagami/kagami/2.0.0/kagami-2.0.0.pom")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("<project></project>");
		assertThat(accessLog()).doesNotContain(directRepositoryUrl());
	}

	/**
	 * Wait for the proxy to log the given request, which it does once the response has
	 * been delivered to Kagami.
	 */
	static void awaitAccessLogEntry(String... expected) {
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(accessLog()).contains(expected));
	}

	static String accessLog() {
		try {
			return squid.execInContainer("cat", "/var/log/squid/access.log").getStdout();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	static String proxiedRepositoryUrl() {
		return "http://host.testcontainers.internal:%d".formatted(mockServer.port());
	}

	static String directRepositoryUrl() {
		return "http://localhost:%d".formatted(mockServer.port());
	}

}
