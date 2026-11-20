package am.ik.kagami.metadata;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the revalidation contract of the mutable index cache: TTL, conditional refetch,
 * stale serving, single-flight and the body size bound.
 */
class MetadataCacheTest {

	private MockServer mockServer;

	private MutableInstantSource clock;

	@BeforeEach
	void setUp() {
		this.mockServer = new MockServer(0);
		this.mockServer.run();
		this.clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
	}

	@AfterEach
	void tearDown() {
		this.mockServer.close();
	}

	@Test
	void revalidatesAfterTheTtlAndServesTheStoredBodyOnNotModified() {
		AtomicInteger hits = new AtomicInteger();
		AtomicInteger conditionalHits = new AtomicInteger();
		String body = "<metadata><versioning><latest>1.0.0</latest></versioning></metadata>";
		this.mockServer.GET("/index/maven-metadata.xml", request -> {
			hits.incrementAndGet();
			if ("\"v1\"".equals(request.header(HttpHeaders.IF_NONE_MATCH))) {
				conditionalHits.incrementAndGet();
				return new Response(304, "", Map.of("ETag", "\"v1\""));
			}
			return Response.builder().body(body).header("ETag", "\"v1\"").build();
		});
		MetadataCache cache = cache(Duration.ofSeconds(30), Duration.ofHours(1), 16, DataSize.ofKilobytes(64));
		KagamiProperties.Repository repository = repository();

		assertThat(cache.get("repo", repository, "index/maven-metadata.xml")).hasValueSatisfying(
				document -> assertThat(new String(document.body(), StandardCharsets.UTF_8)).isEqualTo(body));
		assertThat(hits).hasValue(1);

		this.clock.advance(Duration.ofSeconds(31));

		assertThat(cache.get("repo", repository, "index/maven-metadata.xml")).hasValueSatisfying(
				document -> assertThat(new String(document.body(), StandardCharsets.UTF_8)).isEqualTo(body));
		assertThat(hits).hasValue(2);
		// The second upstream call carried the validator of the stored body and got a 304
		assertThat(conditionalHits).hasValue(1);
	}

	@Test
	void servesStaleContentWithinTheBoundAndFailsAfterwards() {
		AtomicBoolean failing = new AtomicBoolean();
		String body = "<metadata><versioning><latest>1.0.0</latest></versioning></metadata>";
		this.mockServer.GET("/index/maven-metadata.xml", request -> failing.get()
				? Response.builder().status(503).body("unavailable").build() : Response.ok(body));
		MetadataCache cache = cache(Duration.ofSeconds(30), Duration.ofHours(1), 16, DataSize.ofKilobytes(64));
		KagamiProperties.Repository repository = repository();

		cache.get("repo", repository, "index/maven-metadata.xml").orElseThrow();
		failing.set(true);
		this.clock.advance(Duration.ofSeconds(31));

		assertThat(cache.get("repo", repository, "index/maven-metadata.xml")).hasValueSatisfying(
				document -> assertThat(new String(document.body(), StandardCharsets.UTF_8)).isEqualTo(body));

		// The stale body may be served for ttl + staleTtl after the last validation, not
		// longer
		this.clock.advance(Duration.ofHours(1));
		assertThatThrownBy(() -> cache.get("repo", repository, "index/maven-metadata.xml"))
			.isInstanceOf(MetadataUnavailableException.class);
	}

	@Test
	void failsWhenTheUpstreamFailsAndNothingIsCached() {
		this.mockServer.GET("/index/maven-metadata.xml", request -> Response.builder().status(500).body("").build());
		MetadataCache cache = cache(Duration.ofSeconds(30), Duration.ofHours(1), 16, DataSize.ofKilobytes(64));

		assertThatThrownBy(() -> cache.get("repo", repository(), "index/maven-metadata.xml"))
			.isInstanceOf(MetadataUnavailableException.class);
	}

	@Test
	void concurrentRequestsShareOneUpstreamCall() throws Exception {
		AtomicInteger hits = new AtomicInteger();
		String body = "<metadata><versioning><latest>1.0.0</latest></versioning></metadata>";
		this.mockServer.GET("/index/maven-metadata.xml", request -> {
			hits.incrementAndGet();
			// Keep the fetch in flight while the other callers queue up behind it
			LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
			return Response.ok(body);
		});
		MetadataCache cache = cache(Duration.ofSeconds(30), Duration.ofHours(1), 16, DataSize.ofKilobytes(64));
		KagamiProperties.Repository repository = repository();

		int callers = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				results.add(executor.submit(() -> {
					start.await();
					MetadataDocument document = cache.get("repo", repository, "index/maven-metadata.xml").orElseThrow();
					return new String(document.body(), StandardCharsets.UTF_8);
				}));
			}
			start.countDown();
			for (Future<String> result : results) {
				assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(body);
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(hits).hasValue(1);
	}

	@Test
	void rejectsABodyLargerThanTheConfiguredLimit() {
		this.mockServer.GET("/index/maven-metadata.xml", request -> Response.ok("x".repeat(2048)));
		MetadataCache cache = cache(Duration.ofSeconds(30), Duration.ofHours(1), 16, DataSize.ofKilobytes(1));

		assertThatThrownBy(() -> cache.get("repo", repository(), "index/maven-metadata.xml"))
			.isInstanceOf(MetadataUnavailableException.class);
	}

	@Test
	void evictsTheLeastRecentlyUsedEntry() {
		AtomicInteger firstHits = new AtomicInteger();
		this.mockServer.GET("/index/first.xml", request -> {
			firstHits.incrementAndGet();
			return Response.ok("first");
		}).GET("/index/second.xml", request -> Response.ok("second"));
		MetadataCache cache = cache(Duration.ofSeconds(30), Duration.ofHours(1), 1, DataSize.ofKilobytes(64));
		KagamiProperties.Repository repository = repository();

		cache.get("repo", repository, "index/first.xml").orElseThrow();
		cache.get("repo", repository, "index/second.xml").orElseThrow();
		this.clock.advance(Duration.ofSeconds(31));
		cache.get("repo", repository, "index/first.xml").orElseThrow();

		// The evicted entry had to be fetched again
		assertThat(firstHits).hasValue(2);
	}

	private MetadataCache cache(Duration ttl, Duration staleTtl, int maxEntries, DataSize maxBodySize) {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp").build())
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.metadataCache(new KagamiProperties.MetadataCacheConfig(ttl, staleTtl, maxEntries, maxBodySize))
			.build();
		return new MetadataCache(properties, RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				this.clock);
	}

	private KagamiProperties.Repository repository() {
		return KagamiProperties.Repository.builder().url("http://127.0.0.1:" + this.mockServer.port() + "/").build();
	}

	private static final class MutableInstantSource implements InstantSource {

		private Instant instant;

		private MutableInstantSource(Instant instant) {
			this.instant = instant;
		}

		private void advance(Duration duration) {
			this.instant = this.instant.plus(duration);
		}

		@Override
		public Instant instant() {
			return this.instant;
		}

	}

}
