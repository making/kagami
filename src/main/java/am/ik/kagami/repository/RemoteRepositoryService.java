package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.proxy.HttpProxy;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.InstantSource;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Service for fetching artifacts from remote repositories using Maven Resolver.
 * <p>
 * Maven Resolver needs a local repository on disk. Instead of pointing it at Kagami's
 * storage, every fetch resolves into a scratch directory that is discarded afterwards,
 * and the resolved file is copied into {@link StorageService}. This keeps the storage
 * backend the only place where mirrored artifacts live, and keeps the resolver's
 * bookkeeping files ({@code _remote.repositories}, {@code maven-metadata-*.xml}) out of
 * the served layout.
 */
@Service
public class RemoteRepositoryService {

	private static final Logger logger = LoggerFactory.getLogger(RemoteRepositoryService.class);

	private final RepositorySystem repositorySystem;

	private final StorageService storageService;

	private final Map<String, RemoteRepository> repositories;

	private final RestClient restClient;

	private final KagamiProperties kagamiProperties;

	private final UpstreamCircuitBreaker circuitBreaker;

	public RemoteRepositoryService(KagamiProperties properties, StorageService storageService,
			RestClient.Builder restClientBuilder, ProxySettings proxySettings,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder) {
		this.storageService = storageService;
		this.repositories = new ConcurrentHashMap<>();

		// Store properties for later use in RestClient requests
		this.kagamiProperties = properties;

		// The RestClient is already routed through the proxy by the auto-configured
		// request factory; the timeouts keep a stalled upstream from holding the caller
		KagamiProperties.UpstreamConfig upstream = properties.upstream();
		HttpClientSettings settings = HttpClientSettings.defaults()
			.withConnectTimeout(upstream.connectTimeout())
			.withReadTimeout(upstream.requestTimeout());
		this.restClient = restClientBuilder.requestFactory(requestFactoryBuilder.build(settings)).build();
		this.circuitBreaker = new UpstreamCircuitBreaker(upstream.failureThreshold(), upstream.openDuration(),
				InstantSource.system());

		// Initialize Maven Resolver components
		this.repositorySystem = new RepositorySystemSupplier().get();

		// Initialize remote repositories from configuration
		if (properties.repositories() != null) {
			properties.repositories().forEach((repoId, repo) -> {
				if (repoId != null && repo.url() != null) {
					RemoteRepository.Builder repoBuilder = new RemoteRepository.Builder(repoId, "default", repo.url());

					// Add Basic authentication if configured
					if (StringUtils.hasText(repo.username()) && StringUtils.hasText(repo.password())) {
						Authentication auth = new AuthenticationBuilder().addUsername(repo.username())
							.addPassword(repo.password())
							.build();
						repoBuilder.setAuthentication(auth);
						logger.debug("Using Basic authentication for repository {}", repoId);
					}

					// Add proxy support if configured
					proxySettings.proxyFor(repo.url()).ifPresent(proxy -> {
						repoBuilder.setProxy(toResolverProxy(proxy));
						logger.debug("Using proxy {} for repository {}", proxy, repoId);
					});

					this.repositories.put(repoId, repoBuilder.build());
				}
			});
		}
	}

	/**
	 * Fetch an artifact from a remote repository using Maven Resolver
	 * @param location the location of the artifact
	 * @return true if the artifact was successfully fetched and stored, false otherwise
	 */
	public boolean fetchArtifact(ArtifactLocation location) {
		String artifactPath = location.artifactPath();
		String repositoryId = location.repositoryId();
		RemoteRepository repository = this.repositories.get(repositoryId);
		if (repository == null) {
			return false;
		}
		if (!this.circuitBreaker.allowsRequest(repositoryId)) {
			logger.debug("Skipping upstream fetch of {}: the circuit for repository {} is open", artifactPath,
					repositoryId);
			return false;
		}

		// Parse artifact path to create artifact coordinates
		Optional<ArtifactCoordinates> parsed = parseArtifactPath(artifactPath);
		if (parsed.isEmpty()) {
			// If it's not a standard artifact path, fall back to direct HTTP download
			logger.debug("Path is not a standard artifact, using HTTP for: {}", artifactPath);
			return fetchNonStandardFile(location, repository);
		}

		ArtifactCoordinates coords = parsed.get();
		Artifact artifact = new DefaultArtifact(coords.groupId(), coords.artifactId(), coords.classifier(),
				coords.extension(), coords.version());
		ArtifactRequest artifactRequest = new ArtifactRequest();
		artifactRequest.setArtifact(artifact);
		artifactRequest.setRepositories(List.of(repository));

		Path scratchDirectory;
		try {
			scratchDirectory = Files.createTempDirectory("kagami-resolver-");
		}
		catch (IOException e) {
			logger.warn("Failed to create scratch directory for resolving {}", artifactPath, e);
			return false;
		}
		try {
			RepositorySystemSession session = createSession(scratchDirectory);
			ArtifactResult result = this.repositorySystem.resolveArtifact(session, artifactRequest);
			Path resolvedFile = result.isResolved() && result.getArtifact() != null
					&& result.getArtifact().getFile() != null ? result.getArtifact().getFile().toPath() : null;
			if (resolvedFile == null || !Files.isRegularFile(resolvedFile)) {
				// The upstream answered, the artifact simply does not exist there
				this.circuitBreaker.recordSuccess(repositoryId);
				return false;
			}
			try (InputStream inputStream = Files.newInputStream(resolvedFile)) {
				this.storageService.store(location, inputStream);
			}
			fetchSigstoreBundles(location, repository);
			this.circuitBreaker.recordSuccess(repositoryId);
			return true;
		}
		catch (Exception e) {
			// Log error but don't throw - return false to indicate failure
			logger.debug("Failed to fetch artifact via Maven Resolver: {}", artifactPath, e);
			this.circuitBreaker.recordFailure(repositoryId);
			return false;
		}
		finally {
			deleteRecursively(scratchDirectory);
		}
	}

	/**
	 * Outcome of downloading an artifact from a URL found in an upstream index.
	 */
	public enum LinkedFetchOutcome {

		/** The body was stored. */
		STORED,

		/** The upstream answered that the artifact does not exist. */
		NOT_FOUND,

		/** The upstream failed or the body could not be stored. */
		FAILED,

		/** The body was stored but its SHA-256 did not match the index fragment. */
		HASH_MISMATCH

	}

	/**
	 * Download an artifact from an absolute URL found in an upstream index (a PyPI file
	 * link) and stream it into storage. The caller is responsible for validating the URL
	 * host against the repository allowlist before calling this method.
	 * <p>
	 * Basic credentials are only sent when the link stays on the repository host, so a
	 * link to a public file host never leaks the private index credentials. When
	 * {@code sha256} is given the body is hashed while it is streamed; a mismatch removes
	 * the stored object instead of leaving a corrupt artifact behind.
	 * @param location the storage location of the artifact
	 * @param url the absolute upstream URL
	 * @param sha256 the expected SHA-256 in hex, or {@code null} to skip verification
	 * @return the outcome
	 */
	public LinkedFetchOutcome fetchLinkedArtifact(ArtifactLocation location, URI url, @Nullable String sha256) {
		String repositoryId = location.repositoryId();
		if (!this.circuitBreaker.allowsRequest(repositoryId)) {
			logger.debug("Skipping upstream fetch of {}: the circuit for repository {} is open", url, repositoryId);
			return LinkedFetchOutcome.FAILED;
		}
		KagamiProperties.Repository repoConfig = this.kagamiProperties.repositories().get(repositoryId);
		LinkedFetchOutcome outcome;
		try {
			outcome = this.restClient.get().uri(url).headers(headers -> {
				headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
				if (repoConfig != null && StringUtils.hasText(repoConfig.username())
						&& StringUtils.hasText(repoConfig.password()) && sameHost(url, repoConfig.url())) {
					headers.setBasicAuth(repoConfig.username(), repoConfig.password());
				}
			}).exchange((request, response) -> {
				int status = response.getStatusCode().value();
				if (status >= 400 && status < 500) {
					return LinkedFetchOutcome.NOT_FOUND;
				}
				if (status < 200 || status >= 300) {
					return LinkedFetchOutcome.FAILED;
				}
				InputStream body = response.getBody();
				if (body == null) {
					return LinkedFetchOutcome.NOT_FOUND;
				}
				MessageDigest digest = sha256 == null ? null : sha256Digest();
				String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
				try (InputStream decoded = decodedBody(response.getHeaders(), body)) {
					InputStream source = digest == null ? decoded : new DigestInputStream(decoded, digest);
					this.storageService.store(location, source, contentType);
				}
				catch (IOException e) {
					deleteQuietly(location);
					return LinkedFetchOutcome.FAILED;
				}
				if (digest != null && !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha256)) {
					deleteQuietly(location);
					return LinkedFetchOutcome.HASH_MISMATCH;
				}
				return LinkedFetchOutcome.STORED;
			});
		}
		catch (RestClientException e) {
			logger.debug("RestClient error fetching {}: {}", url, e.getMessage());
			outcome = LinkedFetchOutcome.FAILED;
		}
		catch (RuntimeException e) {
			logger.warn("Unexpected error fetching {}", url, e);
			outcome = LinkedFetchOutcome.FAILED;
		}
		if (outcome == LinkedFetchOutcome.STORED || outcome == LinkedFetchOutcome.NOT_FOUND) {
			this.circuitBreaker.recordSuccess(repositoryId);
		}
		else {
			this.circuitBreaker.recordFailure(repositoryId);
		}
		return outcome;
	}

	private void deleteQuietly(ArtifactLocation location) {
		try {
			this.storageService.deleteFile(location);
		}
		catch (IOException e) {
			logger.debug("Failed to remove the rejected download {}", location.artifactPath(), e);
		}
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private static boolean sameHost(URI url, String repositoryUrl) {
		String linkHost = url.getHost();
		try {
			return linkHost != null && linkHost.equalsIgnoreCase(URI.create(repositoryUrl).getHost());
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Fetch non-standard files (like maven-metadata.xml) using direct HTTP. The body is
	 * streamed into storage instead of being buffered, so a large tarball or wheel never
	 * has to fit in the heap.
	 */
	private boolean fetchNonStandardFile(ArtifactLocation location, RemoteRepository repository) {
		String repositoryId = location.repositoryId();
		FetchOutcome outcome = fetchIntoStorage(location, repository);
		switch (outcome) {
			case STORED -> {
				this.circuitBreaker.recordSuccess(repositoryId);
				return true;
			}
			case NOT_FOUND -> {
				// The upstream answered "not here", which is not a failure
				this.circuitBreaker.recordSuccess(repositoryId);
				return false;
			}
			case FAILED -> {
				this.circuitBreaker.recordFailure(repositoryId);
				return false;
			}
		}
		return false;
	}

	/**
	 * Fetch an absolute upstream URL and stream the response body into storage at the
	 * given location.
	 * <p>
	 * The format handlers use it for content whose URL is not derived from the repository
	 * base URL, such as an npm tarball referenced by a packument. Host validation is the
	 * caller's responsibility; the circuit breaker of the repository applies.
	 * @param upstreamUrl the absolute upstream URL to fetch
	 * @param location the storage location the body is stored at
	 * @return the outcome of the fetch
	 */
	public FetchOutcome fetchFromUpstream(URI upstreamUrl, ArtifactLocation location) {
		String repositoryId = location.repositoryId();
		if (!this.circuitBreaker.allowsRequest(repositoryId)) {
			logger.debug("Skipping upstream fetch of {}: the circuit for repository {} is open", upstreamUrl,
					repositoryId);
			return FetchOutcome.FAILED;
		}
		FetchOutcome outcome = fetchIntoStorage(upstreamUrl, location);
		switch (outcome) {
			case STORED, NOT_FOUND -> this.circuitBreaker.recordSuccess(repositoryId);
			case FAILED -> this.circuitBreaker.recordFailure(repositoryId);
		}
		return outcome;
	}

	/**
	 * Download {@code location} from the upstream and stream the response body into
	 * storage.
	 * <p>
	 * The upstream is asked for an identity encoding so that the stored body is the
	 * artifact itself; a compressed response that arrives anyway is decompressed on the
	 * fly. The response body is never buffered, and the upstream Content-Type is recorded
	 * with the object.
	 */
	private FetchOutcome fetchIntoStorage(ArtifactLocation location, RemoteRepository repository) {
		// The artifact path is already encoded and must be sent as is, its path
		// separators must not be encoded into "%2F"
		return fetchIntoStorage(URI.create(repository.getUrl() + "/" + location.artifactPath()), location);
	}

	private FetchOutcome fetchIntoStorage(URI upstreamUrl, ArtifactLocation location) {
		String artifactPath = location.artifactPath();
		KagamiProperties.Repository repoConfig = this.kagamiProperties.repositories().get(location.repositoryId());
		try {
			return this.restClient.get().uri(upstreamUrl).headers(headers -> {
				UpstreamAuthentication.apply(headers, repoConfig);
				headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
			}).exchange((request, response) -> {
				int status = response.getStatusCode().value();
				if (status >= 400 && status < 500) {
					return FetchOutcome.NOT_FOUND;
				}
				if (status < 200 || status >= 300) {
					return FetchOutcome.FAILED;
				}
				InputStream body = response.getBody();
				if (body == null || response.getHeaders().getContentLength() == 0) {
					return FetchOutcome.NOT_FOUND;
				}
				String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
				try (InputStream decoded = decodedBody(response.getHeaders(), body)) {
					this.storageService.store(location, decoded, contentType);
				}
				return FetchOutcome.STORED;
			});
		}
		catch (RestClientException e) {
			logger.debug("RestClient error fetching {}: {}", artifactPath, e.getMessage());
			return FetchOutcome.FAILED;
		}
		catch (Exception e) {
			logger.warn("Unexpected error fetching {}: {}", artifactPath, e);
			return FetchOutcome.FAILED;
		}
	}

	/**
	 * Decode a compressed upstream body so that storage always holds the artifact itself.
	 */
	private static InputStream decodedBody(HttpHeaders headers, InputStream body) throws IOException {
		String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
		if (encoding == null || encoding.isBlank()) {
			return body;
		}
		return switch (encoding.trim().toLowerCase(Locale.ROOT)) {
			case "gzip", "x-gzip" -> gzip(body);
			case "deflate" -> new InflaterInputStream(body);
			default -> body;
		};
	}

	/**
	 * Wrap the body in a gzip decoder only when it really starts with the gzip magic. An
	 * HTTP client that decompresses transparently may leave the {@code Content-Encoding}
	 * header behind; decoding such a body again would fail.
	 */
	private static InputStream gzip(InputStream body) throws IOException {
		PushbackInputStream pushback = new PushbackInputStream(body, 2);
		byte[] magic = new byte[2];
		int read = pushback.readNBytes(magic, 0, magic.length);
		if (read > 0) {
			pushback.unread(magic, 0, read);
		}
		if (read == 2 && magic[0] == (byte) 0x1f && magic[1] == (byte) 0x8b) {
			return new GZIPInputStream(pushback);
		}
		return pushback;
	}

	/**
	 * The result of fetching a non-Maven artifact from the upstream.
	 */
	public enum FetchOutcome {

		/** The body was stored at the requested location. */
		STORED,

		/** The upstream answered with a 4xx, so the artifact does not exist there. */
		NOT_FOUND,

		/** The upstream failed or the circuit is open. */
		FAILED

	}

	/**
	 * Check if a repository is configured
	 * @param repositoryId the repository identifier
	 * @return true if the repository is configured, false otherwise
	 */
	public boolean isRepositoryConfigured(String repositoryId) {
		return this.repositories.containsKey(repositoryId);
	}

	/**
	 * Fetch the sigstore attestation bundles distributed by the upstream as sidecar files
	 * of the artifact at {@code location} (e.g.
	 * {@code <artifact>.attestation.sigstore.json}) and store them next to it. Every
	 * failure is fail-open: a missing or unreadable bundle never affects the artifact
	 * response itself.
	 */
	private void fetchSigstoreBundles(ArtifactLocation location, RemoteRepository repository) {
		KagamiProperties.Repository repoConfig = this.kagamiProperties.repositories().get(location.repositoryId());
		KagamiProperties.Sigstore sigstore = repoConfig == null ? null : repoConfig.sigstore();
		if (sigstore == null || !sigstore.enabled()) {
			return;
		}
		String fileName = location.name();
		List<String> suffixes = Objects.requireNonNullElse(sigstore.bundleSuffixes(),
				KagamiProperties.Sigstore.DEFAULT_BUNDLE_SUFFIXES);
		for (String suffix : suffixes) {
			String bundleName = fileName + "." + suffix;
			ArtifactLocation bundleLocation = location.sibling(bundleName);
			// A bundle absent from the upstream (404) is the normal case: not every
			// artifact carries one, so a failed fetch never affects the artifact response
			if (fetchIntoStorage(bundleLocation, repository) == FetchOutcome.STORED) {
				logger.info("Stored sigstore bundle {}", bundleLocation.artifactPath());
			}
		}
	}

	/**
	 * Create a resolver session whose local repository is the given scratch directory. A
	 * fresh directory per fetch means no locking between concurrent requests.
	 */
	private RepositorySystemSession createSession(Path scratchDirectory) {
		DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
		LocalRepository localRepo = new LocalRepository(scratchDirectory.toFile());
		session.setLocalRepositoryManager(this.repositorySystem.newLocalRepositoryManager(session, localRepo));
		KagamiProperties.UpstreamConfig upstream = this.kagamiProperties.upstream();
		session.setConfigProperty("aether.connector.connectTimeout", (int) upstream.connectTimeout().toMillis());
		session.setConfigProperty("aether.connector.requestTimeout", (int) upstream.requestTimeout().toMillis());
		return session;
	}

	private static void deleteRecursively(Path directory) {
		try (Stream<Path> walk = Files.walk(directory)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
		catch (IOException | UncheckedIOException e) {
			logger.debug("Failed to delete scratch directory {}", directory, e);
		}
	}

	/**
	 * Convert the resolved proxy into the Maven Resolver representation, including the
	 * credentials required by proxies that challenge with Basic authentication.
	 */
	private static Proxy toResolverProxy(HttpProxy proxy) {
		if (!proxy.hasCredentials()) {
			return new Proxy(proxy.scheme(), proxy.host(), proxy.port());
		}
		Authentication authentication = new AuthenticationBuilder().addUsername(proxy.username())
			.addPassword(proxy.password())
			.build();
		return new Proxy(proxy.scheme(), proxy.host(), proxy.port(), authentication);
	}

	/**
	 * Parse artifact path to extract Maven coordinates
	 */
	private Optional<ArtifactCoordinates> parseArtifactPath(String path) {
		// Example: org/springframework/spring-core/6.1.3/spring-core-6.1.3.jar
		// Example: org/springframework/spring-core/6.1.3/spring-core-6.1.3-sources.jar
		// Example: org/springframework/spring-core/6.1.3/spring-core-6.1.3.pom

		String[] parts = path.split("/");
		if (parts.length < 4) {
			return Optional.empty(); // Not a standard Maven path
		}

		// Extract version and filename
		String version = parts[parts.length - 2];
		String filename = parts[parts.length - 1];

		// Extract artifactId
		String artifactId = parts[parts.length - 3];

		// Build groupId
		StringBuilder groupId = new StringBuilder();
		for (int i = 0; i < parts.length - 3; i++) {
			if (i > 0) {
				groupId.append(".");
			}
			groupId.append(parts[i]);
		}

		// Parse filename to get classifier and extension
		String expectedPrefix = artifactId + "-" + version;
		if (!filename.startsWith(expectedPrefix)) {
			return Optional.empty(); // Filename doesn't match expected pattern
		}

		String remainder = filename.substring(expectedPrefix.length());
		String classifier = "";
		String extension = "jar";

		if (remainder.startsWith("-")) {
			// Has classifier
			remainder = remainder.substring(1);
			int dotIndex = remainder.lastIndexOf('.');
			if (dotIndex > 0) {
				classifier = remainder.substring(0, dotIndex);
				extension = remainder.substring(dotIndex + 1);
			}
		}
		else if (remainder.startsWith(".")) {
			// No classifier, just extension
			extension = remainder.substring(1);
		}

		// Handle checksum files
		if (extension.contains(".")) {
			// e.g., jar.sha1 -> extension is jar, but checksum files are not standard
			// artifacts
			return Optional.empty();
		}

		return Optional.of(ArtifactCoordinates.builder()
			.groupId(groupId.toString())
			.artifactId(artifactId)
			.version(version)
			.classifier(classifier)
			.extension(extension)
			.build());
	}

	private record ArtifactCoordinates(String groupId, String artifactId, String version, String classifier,
			String extension) {

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			@Nullable private String groupId;

			@Nullable private String artifactId;

			@Nullable private String version;

			@Nullable private String classifier;

			@Nullable private String extension;

			private Builder() {
			}

			Builder groupId(String groupId) {
				this.groupId = groupId;
				return this;
			}

			Builder artifactId(String artifactId) {
				this.artifactId = artifactId;
				return this;
			}

			Builder version(String version) {
				this.version = version;
				return this;
			}

			Builder classifier(String classifier) {
				this.classifier = classifier;
				return this;
			}

			Builder extension(String extension) {
				this.extension = extension;
				return this;
			}

			ArtifactCoordinates build() {
				return new ArtifactCoordinates(Objects.requireNonNull(this.groupId, "groupId is required"),
						Objects.requireNonNull(this.artifactId, "artifactId is required"),
						Objects.requireNonNull(this.version, "version is required"),
						Objects.requireNonNull(this.classifier, "classifier is required"),
						Objects.requireNonNull(this.extension, "extension is required"));
			}

		}

	}

}
