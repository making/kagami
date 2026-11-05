package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.proxy.HttpProxy;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.InstantSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
	 * Fetch non-standard files (like maven-metadata.xml) using direct HTTP
	 */
	private boolean fetchNonStandardFile(ArtifactLocation location, RemoteRepository repository) {
		// For non-standard files like maven-metadata.xml, we still need HTTP client
		// Maven Resolver doesn't handle these directly
		String artifactPath = location.artifactPath();
		String repositoryId = location.repositoryId();
		try {
			// Get repository configuration for authentication
			KagamiProperties.Repository repoConfig = this.kagamiProperties.repositories().get(location.repositoryId());
			// The artifact path is already encoded and must be sent as is, its path
			// separators must not be encoded into "%2F"
			byte[] responseBytes = this.restClient.get()
				.uri(URI.create(repository.getUrl() + "/" + artifactPath))
				.headers(headers -> {
					if (repoConfig != null && StringUtils.hasText(repoConfig.username())
							&& StringUtils.hasText(repoConfig.password())) {
						headers.setBasicAuth(repoConfig.username(), repoConfig.password());
					}
				})
				.retrieve()
				.body(byte[].class);
			if (responseBytes != null && responseBytes.length > 0) {
				try (InputStream is = new ByteArrayInputStream(responseBytes)) {
					this.storageService.store(location, is);
					this.circuitBreaker.recordSuccess(repositoryId);
					return true;
				}
			}
			this.circuitBreaker.recordSuccess(repositoryId);
		}
		catch (RestClientException e) {
			// A 4xx is the upstream answering "not here", which is not a failure
			if (e instanceof HttpClientErrorException) {
				this.circuitBreaker.recordSuccess(repositoryId);
			}
			else {
				this.circuitBreaker.recordFailure(repositoryId);
			}
			logger.debug("RestClient error fetching non-standard file {}: {}", artifactPath, e.getMessage());
		}
		catch (Exception e) {
			logger.warn("Unexpected error fetching non-standard file: {}", artifactPath, e);
		}
		return false;
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
			try {
				// The bundle path is already encoded and must be sent as is, its path
				// separators must not be encoded into "%2F"
				byte[] bundle = this.restClient.get()
					.uri(URI.create(repository.getUrl() + "/" + bundleLocation.artifactPath()))
					.headers(headers -> {
						if (repoConfig != null && StringUtils.hasText(repoConfig.username())
								&& StringUtils.hasText(repoConfig.password())) {
							headers.setBasicAuth(repoConfig.username(), repoConfig.password());
						}
					})
					.retrieve()
					.body(byte[].class);
				if (bundle != null && bundle.length > 0) {
					try (InputStream inputStream = new ByteArrayInputStream(bundle)) {
						this.storageService.store(bundleLocation, inputStream);
					}
					logger.info("Stored sigstore bundle {}", bundleLocation.artifactPath());
				}
			}
			catch (RestClientException e) {
				// A bundle absent from the upstream (404) is the normal case: not every
				// artifact carries one
				logger.debug("Sigstore bundle {} not available from upstream: {}", bundleName, e.getMessage());
			}
			catch (Exception e) {
				logger.warn("Failed to fetch sigstore bundle {}: {}", bundleName, e.getMessage());
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
