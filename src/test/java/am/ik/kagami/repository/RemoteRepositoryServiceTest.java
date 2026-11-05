package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RemoteRepositoryService
 */
@ExtendWith(MockitoExtension.class)
class RemoteRepositoryServiceTest {

	@Mock
	private StorageService storageService;

	private static KagamiProperties properties(Map<String, KagamiProperties.Repository> repositories,
			KagamiProperties.Proxy proxy) {
		return KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp").build())
			.repositories(repositories)
			.proxy(proxy)
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
	}

	private RemoteRepositoryService service(KagamiProperties properties) {
		return new RemoteRepositoryService(properties, this.storageService, RestClient.builder(),
				ProxySettings.from(properties.proxy(), name -> null), ClientHttpRequestFactoryBuilder.detect());
	}

	@Test
	void testProxyConfigurationPrecedence() {
		// Test property-based proxy configuration
		KagamiProperties properties = properties(Map.of("test",
				KagamiProperties.Repository.builder()
					.url("http://example.com")
					.username("")
					.password("")
					.isPrivate(true)
					.build()),
				KagamiProperties.Proxy.builder().url("http://config-proxy:8080").build());

		// Verify service was created successfully
		assertThat(service(properties).isRepositoryConfigured("test")).isTrue();
	}

	@Test
	void testEmptyProxyConfiguration() {
		// Test with no proxy configuration
		KagamiProperties properties = properties(Map.of("test",
				KagamiProperties.Repository.builder()
					.url("http://example.com")
					.username("")
					.password("")
					.isPrivate(true)
					.build()),
				KagamiProperties.Proxy.builder().url("").build());

		// Verify service was created successfully
		assertThat(service(properties).isRepositoryConfigured("test")).isTrue();
	}

	@Test
	void testBasicAuthConfiguration() {
		// Test Basic authentication configuration
		KagamiProperties properties = properties(Map.of("authenticated-repo",
				KagamiProperties.Repository.builder()
					.url("http://private.example.com")
					.username("user")
					.password("pass")
					.isPrivate(true)
					.build()),
				KagamiProperties.Proxy.builder().url("").build());

		// Verify service was created successfully
		assertThat(service(properties).isRepositoryConfigured("authenticated-repo")).isTrue();
	}

	@Test
	void failingUpstreamIsShortCircuitedAfterTheConfiguredThreshold() throws Exception {
		AtomicInteger hits = new AtomicInteger();
		HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/", exchange -> {
			hits.incrementAndGet();
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		upstream.start();
		try {
			String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
			KagamiProperties properties = KagamiProperties.builder()
				.storage(KagamiProperties.Storage.builder().path("/tmp").build())
				.repositories(Map.of("repo", KagamiProperties.Repository.builder().url(url).isPrivate(false).build()))
				.jwt(new KagamiProperties.Jwt(null, null))
				.authentication(
						new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
				.upstream(new KagamiProperties.UpstreamConfig(Duration.ofSeconds(1), Duration.ofSeconds(2), 2,
						Duration.ofSeconds(30)))
				.build();
			RemoteRepositoryService service = service(properties);

			for (int i = 0; i < 5; i++) {
				service.fetchArtifact(new ArtifactLocation("repo", "maven-metadata.xml"));
			}

			// Two failures open the circuit; the remaining requests never reach the
			// upstream instead of each paying the timeout
			assertThat(hits.get()).isEqualTo(2);
		}
		finally {
			upstream.stop(0);
		}
	}

}
