package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.TestSocketUtils;
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
		return properties(repositories, proxy, "/tmp");
	}

	private static KagamiProperties properties(Map<String, KagamiProperties.Repository> repositories,
			KagamiProperties.Proxy proxy, String storagePath) {
		return KagamiProperties.builder()
			.storage(new KagamiProperties.Storage(storagePath))
			.repositories(repositories)
			.proxy(proxy)
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
	}

	private RemoteRepositoryService service(KagamiProperties properties) {
		return new RemoteRepositoryService(properties, this.storageService, RestClient.builder(),
				ProxySettings.from(properties.proxy(), name -> null));
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
	void concurrentFetchesOfTheSameArtifactShareASingleDownload(@TempDir Path storagePath) throws Exception {
		int port = TestSocketUtils.findAvailableTcpPort();
		AtomicInteger requestCount = new AtomicInteger();
		ArtifactLocation location = new ArtifactLocation("mock", "com/example/demo/maven-metadata.xml");
		try (MockServer mockServer = new MockServer(port)) {
			mockServer.GET("/com/example/demo/maven-metadata.xml", request -> {
				requestCount.incrementAndGet();
				sleep(500);
				return Response.ok("<metadata></metadata>");
			});
			mockServer.run();
			KagamiProperties properties = properties(Map.of("mock",
					KagamiProperties.Repository.builder()
						.url("http://127.0.0.1:%d".formatted(port))
						.username("")
						.password("")
						.isPrivate(false)
						.build()),
					KagamiProperties.Proxy.builder().url("").build(), storagePath.toString());
			RemoteRepositoryService service = service(properties);

			// Two requests ask for the same artifact at the same time
			CyclicBarrier startTogether = new CyclicBarrier(2);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Callable<Boolean> fetch = () -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return service.fetchArtifact(location);
				};
				Future<Boolean> first = executor.submit(fetch);
				Future<Boolean> second = executor.submit(fetch);

				assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
				assertThat(second.get(30, TimeUnit.SECONDS)).isTrue();
			}
			finally {
				executor.shutdownNow();
			}
		}

		// The second request must reuse the download started by the first one
		assertThat(requestCount).hasValue(1);
	}

	private static void sleep(long millis) {
		try {
			TimeUnit.MILLISECONDS.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
