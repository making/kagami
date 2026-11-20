package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

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

	private static KagamiProperties propertiesFor(String url) {
		return KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp").build())
			.repositories(Map.of("repo", KagamiProperties.Repository.builder().url(url).isPrivate(false).build()))
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.upstream(new KagamiProperties.UpstreamConfig(Duration.ofSeconds(1), Duration.ofSeconds(10), 2,
					Duration.ofSeconds(30), List.of()))
			.build();
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
	void nonStandardFetchStreamsTheResponseBodyIntoStorage() throws Exception {
		byte[] payload = new byte[512 * 1024];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) i;
		}
		CountDownLatch storageStartedReading = new CountDownLatch(1);
		AtomicBoolean upstreamReleasedBeforeCompletion = new AtomicBoolean();
		AtomicReference<String> acceptEncoding = new AtomicReference<>();
		HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/", exchange -> {
			acceptEncoding.set(exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING));
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				int half = payload.length / 2;
				out.write(payload, 0, half);
				out.flush();
				// The upstream can only finish once storage started consuming the first
				// half, which is exactly what streaming means. A buffering client would
				// deadlock here until the wait times out.
				upstreamReleasedBeforeCompletion.set(storageStartedReading.await(10, TimeUnit.SECONDS));
				out.write(payload, half, payload.length - half);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		upstream.start();
		try {
			AtomicInteger storedBytes = new AtomicInteger();
			doAnswer(invocation -> {
				InputStream body = invocation.getArgument(1);
				storageStartedReading.countDown();
				storedBytes.addAndGet((int) body.transferTo(OutputStream.nullOutputStream()));
				return null;
			}).when(this.storageService)
				.store(any(ArtifactLocation.class), any(InputStream.class), nullable(String.class));

			String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
			boolean fetched = service(propertiesFor(url))
				.fetchArtifact(new ArtifactLocation("repo", "packages/foo-1.0.0.tgz"));

			assertThat(fetched).isTrue();
			assertThat(upstreamReleasedBeforeCompletion).as("the upstream streamed while storage was reading").isTrue();
			assertThat(storedBytes).hasValue(payload.length);
			assertThat(acceptEncoding).hasValue("identity");
		}
		finally {
			upstream.stop(0);
		}
	}

	@Test
	void gzipEncodedUpstreamResponseIsStoredDecompressed() throws Exception {
		byte[] content = "the wheel payload".getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
		try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
			out.write(content);
		}
		byte[] gzippedBytes = gzipped.toByteArray();
		HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/", exchange -> {
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
			exchange.sendResponseHeaders(200, gzippedBytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(gzippedBytes);
			}
		});
		upstream.start();
		try {
			AtomicReference<byte[]> storedBody = new AtomicReference<>();
			AtomicReference<String> storedContentType = new AtomicReference<>();
			doAnswer(invocation -> {
				storedBody.set(((InputStream) invocation.getArgument(1)).readAllBytes());
				storedContentType.set(invocation.getArgument(2));
				return null;
			}).when(this.storageService)
				.store(any(ArtifactLocation.class), any(InputStream.class), nullable(String.class));

			String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
			boolean fetched = service(propertiesFor(url))
				.fetchArtifact(new ArtifactLocation("repo", "packages/foo-1.0.0-py3-none-any.whl"));

			assertThat(fetched).isTrue();
			assertThat(storedBody.get()).isEqualTo(content);
			// The upstream Content-Type is recorded with the artifact
			assertThat(storedContentType).hasValue("application/octet-stream");
		}
		finally {
			upstream.stop(0);
		}
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
						Duration.ofSeconds(30), List.of()))
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
