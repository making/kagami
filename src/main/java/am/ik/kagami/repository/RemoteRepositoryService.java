package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.proxy.HttpProxy;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Service for fetching artifacts from remote repositories using Maven Resolver
 */
@Service
public class RemoteRepositoryService {

	private static final Logger logger = LoggerFactory.getLogger(RemoteRepositoryService.class);

	private final RepositorySystem repositorySystem;

	private final StorageService storageService;

	private final Map<String, RemoteRepository> repositories;

	private final Map<String, RepositorySystemSession> sessions;

	private final RestClient restClient;

	private final KagamiProperties kagamiProperties;

	public RemoteRepositoryService(KagamiProperties properties, StorageService storageService,
			RestClient.Builder restClientBuilder, ProxySettings proxySettings) {
		this.storageService = storageService;
		this.repositories = new ConcurrentHashMap<>();
		this.sessions = new ConcurrentHashMap<>();

		// Store properties for later use in RestClient requests
		this.kagamiProperties = properties;

		// The RestClient is already routed through the proxy by the auto-configured
		// request factory
		this.restClient = restClientBuilder.build();

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

					RemoteRepository remoteRepo = repoBuilder.build();
					this.repositories.put(repoId, remoteRepo);

					// Create repository-specific session
					this.sessions.put(repoId, createSession(repoId));
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
		RemoteRepository repository = this.repositories.get(location.repositoryId());
		RepositorySystemSession session = this.sessions.get(location.repositoryId());
		if (repository == null || session == null) {
			return false;
		}

		try {
			// Parse artifact path to create artifact coordinates
			Optional<ArtifactCoordinates> parsed = parseArtifactPath(artifactPath);
			if (parsed.isEmpty()) {
				// If it's not a standard artifact path, fall back to direct HTTP download
				logger.debug("Path is not a standard artifact, using HTTP for: {}", artifactPath);
				boolean success = fetchNonStandardFile(location, repository);
				if (!success) {
					cleanupEmptyDirectories(location);
				}
				return success;
			}

			// Create artifact
			ArtifactCoordinates coords = parsed.get();
			Artifact artifact = new DefaultArtifact(coords.groupId(), coords.artifactId(), coords.classifier(),
					coords.extension(), coords.version());

			// Create artifact request
			ArtifactRequest artifactRequest = new ArtifactRequest();
			artifactRequest.setArtifact(artifact);
			artifactRequest.setRepositories(List.of(repository));

			// Resolve artifact
			ArtifactResult result = this.repositorySystem.resolveArtifact(session, artifactRequest);

			if (result.isResolved() && result.getArtifact() != null) {
				File resolvedFile = result.getArtifact().getFile();
				if (resolvedFile != null && resolvedFile.exists()) {
					// Maven Resolver has already stored the artifact in
					// repository-specific directory
					return true;
				}
			}
		}
		catch (Exception e) {
			// Log error but don't throw - return false to indicate failure
			logger.debug("Failed to fetch artifact via Maven Resolver: {}", artifactPath, e);
			cleanupEmptyDirectories(location);
		}

		return false;
	}

	/**
	 * Fetch non-standard files (like maven-metadata.xml) using direct HTTP
	 */
	private boolean fetchNonStandardFile(ArtifactLocation location, RemoteRepository repository) {
		// For non-standard files like maven-metadata.xml, we still need HTTP client
		// Maven Resolver doesn't handle these directly
		String artifactPath = location.artifactPath();
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
					return true;
				}
			}
		}
		catch (RestClientException e) {
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

	private RepositorySystemSession createSession(String repositoryId) {
		DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

		// Use repository-specific directory within Kagami's storage path
		// This eliminates duplicate storage while keeping repositories separate
		Path storagePath = Path.of(this.kagamiProperties.storage().path()).resolve(repositoryId);
		try {
			Files.createDirectories(storagePath);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to create storage directory: " + storagePath, e);
		}

		LocalRepository localRepo = new LocalRepository(storagePath.toFile());
		session.setLocalRepositoryManager(this.repositorySystem.newLocalRepositoryManager(session, localRepo));

		return session;
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

	/**
	 * Clean up empty directories that may have been created during failed fetch attempts
	 */
	private void cleanupEmptyDirectories(ArtifactLocation location) {
		String artifactPath = location.artifactPath();
		try {
			Path storagePath = Path.of(this.kagamiProperties.storage().path()).resolve(location.repositoryId());
			Path artifactDir = storagePath.resolve(artifactPath).getParent();

			// Walk up the directory tree and remove empty directories
			while (artifactDir != null && artifactDir.startsWith(storagePath) && !artifactDir.equals(storagePath)) {
				if (Files.exists(artifactDir) && Files.isDirectory(artifactDir)) {
					try (Stream<Path> stream = Files.list(artifactDir)) {
						if (stream.findFirst().isEmpty()) {
							// Directory is empty, remove it
							Files.delete(artifactDir);
							logger.debug("Removed empty directory: {}", artifactDir);
						}
						else {
							// Directory is not empty, stop cleanup
							break;
						}
					}
				}
				artifactDir = artifactDir.getParent();
			}
		}
		catch (Exception e) {
			logger.debug("Failed to cleanup empty directories for {}: {}", artifactPath, e.getMessage());
		}
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