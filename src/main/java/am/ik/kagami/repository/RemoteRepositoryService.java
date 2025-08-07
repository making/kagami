package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
			RestClient.Builder restClientBuilder) {
		this.storageService = storageService;
		this.repositories = new ConcurrentHashMap<>();
		this.sessions = new ConcurrentHashMap<>();

		// Store properties for later use in RestClient requests
		this.kagamiProperties = properties;

		// Determine proxy configuration early for both Maven Resolver and RestClient
		String proxyUrl = determineProxyUrl(properties);
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
					if (StringUtils.hasText(proxyUrl)) {
						try {
							URL url = URI.create(proxyUrl).toURL();
							repoBuilder.setProxy(new Proxy(url.getProtocol(), url.getHost(), url.getPort()));
							logger.debug("Using proxy {} for repository {}", proxyUrl, repoId);
						}
						catch (MalformedURLException e) {
							logger.warn("Invalid proxy URL: {}", proxyUrl, e);
						}
					}

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
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact
	 * @return true if the artifact was successfully fetched and stored, false otherwise
	 */
	public boolean fetchArtifact(String repositoryId, String artifactPath) {
		RemoteRepository repository = this.repositories.get(repositoryId);
		RepositorySystemSession session = this.sessions.get(repositoryId);
		if (repository == null || session == null) {
			return false;
		}

		try {
			// Parse artifact path to create artifact coordinates
			ArtifactCoordinates coords = parseArtifactPath(artifactPath);
			if (coords == null) {
				// If it's not a standard artifact path, fall back to direct HTTP download
				logger.debug("Path is not a standard artifact, using HTTP for: {}", artifactPath);
				boolean success = fetchNonStandardFile(repositoryId, artifactPath, repository);
				if (!success) {
					cleanupEmptyDirectories(repositoryId, artifactPath);
				}
				return success;
			}

			// Create artifact
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
			cleanupEmptyDirectories(repositoryId, artifactPath);
		}

		return false;
	}

	/**
	 * Fetch non-standard files (like maven-metadata.xml) using direct HTTP
	 */
	private boolean fetchNonStandardFile(String repositoryId, String artifactPath, RemoteRepository repository) {
		// For non-standard files like maven-metadata.xml, we still need HTTP client
		// Maven Resolver doesn't handle these directly
		try {
			// Get repository configuration for authentication
			KagamiProperties.Repository repoConfig = this.kagamiProperties.repositories().get(repositoryId);
			byte[] responseBytes = this.restClient.get()
				.uri(repository.getUrl() + "/{artifactPath}", artifactPath)
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
					this.storageService.store(repositoryId, artifactPath, is);
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
	 * Determine proxy URL from configuration or environment variables Priority: 1.
	 * kagami.proxy.url property, 2. http_proxy env var, 3. HTTP_PROXY env var
	 */
	private String determineProxyUrl(KagamiProperties properties) {
		// 1. Check configuration property
		if (properties.proxy() != null && StringUtils.hasText(properties.proxy().url())) {
			logger.debug("Using proxy from configuration: {}", properties.proxy().url());
			return properties.proxy().url();
		}

		// 2. Check lowercase http_proxy environment variable
		String httpProxy = System.getenv("http_proxy");
		if (StringUtils.hasText(httpProxy)) {
			logger.debug("Using proxy from http_proxy environment variable: {}", httpProxy);
			return httpProxy;
		}

		// 3. Check uppercase HTTP_PROXY environment variable
		String httpProxyUpper = System.getenv("HTTP_PROXY");
		if (StringUtils.hasText(httpProxyUpper)) {
			logger.debug("Using proxy from HTTP_PROXY environment variable: {}", httpProxyUpper);
			return httpProxyUpper;
		}

		return null;
	}

	/**
	 * Parse artifact path to extract Maven coordinates
	 */
	private ArtifactCoordinates parseArtifactPath(String path) {
		// Example: org/springframework/spring-core/6.1.3/spring-core-6.1.3.jar
		// Example: org/springframework/spring-core/6.1.3/spring-core-6.1.3-sources.jar
		// Example: org/springframework/spring-core/6.1.3/spring-core-6.1.3.pom

		String[] parts = path.split("/");
		if (parts.length < 4) {
			return null; // Not a standard Maven path
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
			return null; // Filename doesn't match expected pattern
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
			// e.g., jar.sha1 -> extension is jar, but we'll return null for checksum
			// files
			return null;
		}

		return new ArtifactCoordinates(groupId.toString(), artifactId, version, classifier, extension);
	}

	/**
	 * Clean up empty directories that may have been created during failed fetch attempts
	 */
	private void cleanupEmptyDirectories(String repositoryId, String artifactPath) {
		try {
			Path storagePath = Path.of(this.kagamiProperties.storage().path()).resolve(repositoryId);
			Path artifactDir = storagePath.resolve(artifactPath).getParent();

			// Walk up the directory tree and remove empty directories
			while (artifactDir != null && artifactDir.startsWith(storagePath) && !artifactDir.equals(storagePath)) {
				if (Files.exists(artifactDir) && Files.isDirectory(artifactDir)) {
					try (var stream = Files.list(artifactDir)) {
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
	}

}