package am.ik.kagami.metadata;

import am.ik.kagami.KagamiProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * In-memory cache of mutable upstream indexes such as {@code maven-metadata.xml}, npm
 * packuments and PyPI simple indexes.
 * <p>
 * Indexes are kept out of the artifact storage on purpose: they contain absolute upstream
 * URLs, their Content-Type cannot be preserved by the extension based storage, and they
 * would appear in the browse listing as if they were artifacts.
 * <p>
 * An entry is served without contacting the upstream for {@code ttl}. Afterwards it is
 * revalidated with a conditional request ({@code If-None-Match} /
 * {@code If-Modified-Since}); a {@code 304} refreshes the entry without downloading the
 * body again. While the upstream fails, the last body is served for at most
 * {@code staleTtl} beyond the TTL, after which the request fails instead of serving
 * content that may be arbitrarily old.
 * <p>
 * Concurrent requests for the same repository and upstream URL share a single upstream
 * call. The cache is in memory and per instance: replicas do not share it, so every
 * instance revalidates after its own TTL.
 */
@Component
public class MetadataCache {

	private static final Logger logger = LoggerFactory.getLogger(MetadataCache.class);

	private static final int MAX_BODY_READ_BUFFER = 8192;

	private final RestClient restClient;

	private final InstantSource clock;

	private final KagamiProperties.MetadataCacheConfig settings;

	private final Map<Key, CachedIndex> entries;

	private final ConcurrentMap<Key, CompletableFuture<Optional<MetadataDocument>>> inFlight = new ConcurrentHashMap<>();

	public MetadataCache(KagamiProperties properties, RestClient.Builder restClientBuilder,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder, InstantSource clock) {
		KagamiProperties.UpstreamConfig upstream = properties.upstream();
		HttpClientSettings clientSettings = HttpClientSettings.defaults()
			.withConnectTimeout(upstream.connectTimeout())
			.withReadTimeout(upstream.requestTimeout());
		this.restClient = restClientBuilder.requestFactory(requestFactoryBuilder.build(clientSettings)).build();
		this.clock = clock;
		this.settings = properties.metadataCache();
		this.entries = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Key, CachedIndex> eldest) {
				return size() > MetadataCache.this.settings.maxEntries();
			}
		};
	}

	/**
	 * Serve an index from the cache, revalidating or fetching it from the upstream when
	 * it is missing or its TTL has elapsed.
	 * @param repositoryId the repository the index belongs to
	 * @param repository the configuration of that repository
	 * @param indexPath the index path relative to the repository root, already
	 * URL-decoded
	 * @return the document, or an empty optional if the upstream answered {@code 404}
	 * @throws MetadataUnavailableException if the upstream failed and no sufficiently
	 * fresh stale body is available
	 */
	public Optional<MetadataDocument> get(String repositoryId, KagamiProperties.Repository repository,
			String indexPath) {
		String upstreamUrl = upstreamUrl(repository, indexPath);
		Key key = new Key(repositoryId, upstreamUrl);

		Optional<MetadataDocument> fresh = fresh(key);
		if (fresh != null) {
			return fresh;
		}

		CompletableFuture<Optional<MetadataDocument>> future = new CompletableFuture<>();
		CompletableFuture<Optional<MetadataDocument>> running = this.inFlight.putIfAbsent(key, future);
		if (running != null) {
			return await(running);
		}
		try {
			// Another request may have completed and removed its entry while this one was
			// waiting for the in-flight slot
			Optional<MetadataDocument> justFetched = fresh(key);
			Optional<MetadataDocument> result = justFetched != null ? justFetched
					: revalidate(key, repository, upstreamUrl);
			future.complete(result);
			return result;
		}
		catch (RuntimeException e) {
			future.completeExceptionally(e);
			throw e;
		}
		finally {
			this.inFlight.remove(key, future);
		}
	}

	private @Nullable Optional<MetadataDocument> fresh(Key key) {
		synchronized (this.entries) {
			CachedIndex entry = this.entries.get(key);
			if (entry == null) {
				return null;
			}
			if (this.clock.instant().isBefore(entry.lastValidatedAt().plus(this.settings.ttl()))) {
				return Optional.of(entry.document());
			}
			return null;
		}
	}

	private Optional<MetadataDocument> await(CompletableFuture<Optional<MetadataDocument>> future) {
		try {
			return future.join();
		}
		catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw e;
		}
	}

	private Optional<MetadataDocument> revalidate(Key key, KagamiProperties.Repository repository, String upstreamUrl) {
		CachedIndex previous = entry(key);
		UpstreamResponse response;
		try {
			response = fetch(repository, upstreamUrl, previous);
		}
		catch (MetadataUnavailableException e) {
			return stale(previous, upstreamUrl, e);
		}
		catch (RestClientException e) {
			logger.debug("Failed to revalidate index {}: {}", upstreamUrl, e.getMessage());
			return stale(previous, upstreamUrl, e);
		}

		int status = response.status();
		if (status == 304) {
			if (previous == null) {
				throw new MetadataUnavailableException("Upstream answered 304 without a cached index: " + upstreamUrl);
			}
			// The stored body is still current; only the freshness timestamp changes
			store(key, new CachedIndex(previous.document(), this.clock.instant()));
			return Optional.of(previous.document());
		}
		if (status >= 200 && status < 300) {
			MetadataDocument document = new MetadataDocument(response.body(), contentType(response.contentType()),
					response.etag(), response.lastModified(), this.clock.instant());
			store(key, new CachedIndex(document, this.clock.instant()));
			return Optional.of(document);
		}
		if (status == 404) {
			remove(key);
			return Optional.empty();
		}
		return stale(previous, upstreamUrl,
				new MetadataUnavailableException("Upstream returned " + status + " for " + upstreamUrl));
	}

	private @Nullable CachedIndex entry(Key key) {
		synchronized (this.entries) {
			return this.entries.get(key);
		}
	}

	private void store(Key key, CachedIndex entry) {
		synchronized (this.entries) {
			this.entries.put(key, entry);
		}
	}

	private void remove(Key key) {
		synchronized (this.entries) {
			this.entries.remove(key);
		}
	}

	/**
	 * Serve the last stored body while the upstream failure is within the stale bound.
	 */
	private Optional<MetadataDocument> stale(@Nullable CachedIndex previous, String upstreamUrl,
			RuntimeException cause) {
		if (previous == null) {
			if (cause instanceof MetadataUnavailableException metadataUnavailableException) {
				throw metadataUnavailableException;
			}
			throw new MetadataUnavailableException("Failed to fetch index " + upstreamUrl + ": " + cause.getMessage(),
					cause);
		}
		Instant staleUntil = previous.lastValidatedAt().plus(this.settings.ttl()).plus(this.settings.staleTtl());
		if (this.clock.instant().isBefore(staleUntil)) {
			logger.debug("Serving stale index {} after an upstream failure: {}", upstreamUrl, cause.getMessage());
			return Optional.of(previous.document());
		}
		throw new MetadataUnavailableException(
				"Index " + upstreamUrl + " is stale and the upstream keeps failing: " + cause.getMessage(), cause);
	}

	private UpstreamResponse fetch(KagamiProperties.Repository repository, String upstreamUrl,
			@Nullable CachedIndex previous) {
		URI uri = URI.create(upstreamUrl);
		return this.restClient.get().uri(uri).headers(headers -> {
			// A compressed body would be stored as if it were the index itself
			headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
			if (StringUtils.hasText(repository.username()) && StringUtils.hasText(repository.password())) {
				headers.setBasicAuth(repository.username(), repository.password());
			}
			if (previous != null) {
				String etag = previous.document().etag();
				if (etag != null) {
					headers.setIfNoneMatch(etag);
				}
				String lastModified = previous.document().lastModified();
				if (lastModified != null) {
					headers.set(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
				}
			}
		}).exchange((request, response) -> {
			int status = response.getStatusCode().value();
			HttpHeaders responseHeaders = response.getHeaders();
			byte[] body = status >= 200 && status < 300 ? readBounded(response.getBody(), upstreamUrl) : new byte[0];
			return new UpstreamResponse(status, body, responseHeaders.getFirst(HttpHeaders.CONTENT_TYPE),
					responseHeaders.getETag(), responseHeaders.getFirst(HttpHeaders.LAST_MODIFIED));
		});
	}

	/**
	 * Read an upstream body up to the configured limit. The stream is consumed without
	 * buffering more than the limit, so a pathological upstream cannot exhaust the heap.
	 */
	private byte[] readBounded(InputStream body, String upstreamUrl) throws IOException {
		long limit = this.settings.maxBodySize().toBytes();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[MAX_BODY_READ_BUFFER];
		long total = 0;
		int read;
		while ((read = body.read(buffer)) != -1) {
			total += read;
			if (total > limit) {
				throw new MetadataUnavailableException(
						"Index body of " + upstreamUrl + " exceeds the limit of " + limit + " bytes");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static @Nullable MediaType contentType(@Nullable String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return MediaType.parseMediaType(value);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String upstreamUrl(KagamiProperties.Repository repository, String indexPath) {
		String base = Objects.requireNonNull(repository.url(), "repository url is required").trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		if (!StringUtils.hasText(indexPath)) {
			return base;
		}
		return base + "/" + indexPath;
	}

	/**
	 * Key of an entry: the repository plus the upstream URL, never the request path, so
	 * that two request paths that resolve to the same upstream document share one entry.
	 */
	private record Key(String repositoryId, String upstreamUrl) {
	}

	private record CachedIndex(MetadataDocument document, Instant lastValidatedAt) {
	}

	private record UpstreamResponse(int status, byte[] body, @Nullable String contentType, @Nullable String etag,
			@Nullable String lastModified) {
	}

}
