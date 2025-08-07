package am.ik.kagami.browser;

import am.ik.kagami.KagamiProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Service for browsing repository contents
 */
@Service
public class BrowserService {

	private final Path basePath;

	private final KagamiProperties properties;

	public BrowserService(KagamiProperties properties) {
		this.properties = properties;
		this.basePath = Path.of(properties.storage().path()).toAbsolutePath().normalize();
	}

	/**
	 * Get all configured repositories with their statistics
	 * @return list of repository information
	 */
	public List<RepositoryInfo> getRepositories() {
		List<RepositoryInfo> repositories = new ArrayList<>();

		if (properties.repositories() != null) {
			for (Map.Entry<String, KagamiProperties.Repository> entry : properties.repositories().entrySet()) {
				String repoId = entry.getKey();
				String url = entry.getValue().url();

				// Calculate repository statistics
				Path repoPath = basePath.resolve(repoId);
				long artifactCount = 0;
				long totalSize = 0;
				Instant lastUpdated = null;

				if (Files.exists(repoPath)) {
					try {
						RepositoryStats stats = calculateRepositoryStats(repoPath);
						artifactCount = stats.artifactCount();
						totalSize = stats.totalSize();
						lastUpdated = stats.lastUpdated();
					}
					catch (IOException e) {
						// Log error but continue with zero values
					}
				}

				repositories.add(new RepositoryInfo(repoId, url, artifactCount, totalSize, lastUpdated));
			}
		}

		return repositories;
	}

	/**
	 * Browse repository contents at the specified path
	 * @param repositoryId the repository identifier
	 * @param path the path within the repository (null or empty for root)
	 * @return browse result with entries
	 */
	public BrowseResult browseRepository(String repositoryId, String path) throws IOException {
		// Validate repository exists
		if (!properties.repositories().containsKey(repositoryId)) {
			throw new IllegalArgumentException("Repository not found: " + repositoryId);
		}

		// Normalize and validate path
		String normalizedPath = normalizePath(path);
		Path targetPath = basePath.resolve(repositoryId);
		if (StringUtils.hasText(normalizedPath)) {
			targetPath = targetPath.resolve(normalizedPath);
		}

		// Ensure path is within repository boundaries
		targetPath = targetPath.normalize();
		Path repoRoot = basePath.resolve(repositoryId).normalize();
		if (!targetPath.startsWith(repoRoot)) {
			throw new IllegalArgumentException("Invalid path: " + path);
		}

		// Check if path exists
		if (!Files.exists(targetPath)) {
			return new BrowseResult(repositoryId, normalizedPath, getParentPath(normalizedPath), List.of());
		}

		// List directory contents
		List<RepositoryEntry> entries = new ArrayList<>();
		try (Stream<Path> stream = Files.list(targetPath)) {
			stream.sorted(Comparator.comparing(Path::getFileName)).forEach(entryPath -> {
				try {
					RepositoryEntry entry = createRepositoryEntry(repositoryId, repoRoot, entryPath);
					entries.add(entry);
				}
				catch (IOException e) {
					// Skip entries that can't be read
				}
			});
		}

		return new BrowseResult(repositoryId, normalizedPath, getParentPath(normalizedPath), entries);
	}

	/**
	 * Get detailed information about a file
	 * @param repositoryId the repository identifier
	 * @param path the file path
	 * @return file information
	 */
	public FileInfo getFileInfo(String repositoryId, String path) throws IOException {
		// Validate repository exists
		if (!properties.repositories().containsKey(repositoryId)) {
			throw new IllegalArgumentException("Repository not found: " + repositoryId);
		}

		// Normalize and validate path
		String normalizedPath = normalizePath(path);
		if (!StringUtils.hasText(normalizedPath)) {
			throw new IllegalArgumentException("Path is required");
		}

		Path targetPath = basePath.resolve(repositoryId).resolve(normalizedPath).normalize();
		Path repoRoot = basePath.resolve(repositoryId).normalize();

		// Ensure path is within repository boundaries
		if (!targetPath.startsWith(repoRoot)) {
			throw new IllegalArgumentException("Invalid path: " + path);
		}

		// Check if file exists
		if (!Files.exists(targetPath) || !Files.isRegularFile(targetPath)) {
			throw new IllegalArgumentException("File not found: " + path);
		}

		// Get file information
		String fileName = targetPath.getFileName().toString();
		long size = Files.size(targetPath);
		Instant lastModified = Files.getLastModifiedTime(targetPath).toInstant();
		String contentType = determineContentType(fileName);

		// Calculate checksums if they exist
		Path sha1Path = targetPath.resolveSibling(fileName + ".sha1");
		Path sha256Path = targetPath.resolveSibling(fileName + ".sha256");

		String sha1 = null;
		String sha256 = null;

		if (Files.exists(sha1Path)) {
			sha1 = Files.readString(sha1Path).trim();
		}
		if (Files.exists(sha256Path)) {
			sha256 = Files.readString(sha256Path).trim();
		}

		return new FileInfo(repositoryId, normalizedPath, fileName, "file", size, lastModified, contentType, sha1,
				sha256);
	}

	private RepositoryEntry createRepositoryEntry(String repositoryId, Path repoRoot, Path entryPath)
			throws IOException {
		String relativePath = repoRoot.relativize(entryPath).toString().replace('\\', '/');
		String name = entryPath.getFileName().toString();
		String type = Files.isDirectory(entryPath) ? "directory" : "file";
		Instant lastModified = Files.getLastModifiedTime(entryPath).toInstant();

		if ("file".equals(type)) {
			long size = Files.size(entryPath);
			return new RepositoryEntry(name, type, relativePath, size, lastModified);
		}
		else {
			return new RepositoryEntry(name, type, relativePath, null, lastModified);
		}
	}

	private RepositoryStats calculateRepositoryStats(Path repoPath) throws IOException {
		long artifactCount = 0;
		long totalSize = 0;
		Instant lastUpdated = Instant.MIN;

		try (Stream<Path> stream = Files.walk(repoPath)) {
			List<Path> files = stream.filter(Files::isRegularFile).toList();

			for (Path file : files) {
				// Count only main artifacts (skip checksums and metadata)
				String fileName = file.getFileName().toString();
				if (!fileName.endsWith(".sha1") && !fileName.endsWith(".sha256") && !fileName.endsWith(".md5")
						&& !fileName.equals("maven-metadata.xml") && !fileName.equals("_remote.repositories")) {
					artifactCount++;
				}

				totalSize += Files.size(file);
				Instant modified = Files.getLastModifiedTime(file).toInstant();
				if (modified.isAfter(lastUpdated)) {
					lastUpdated = modified;
				}
			}
		}

		return new RepositoryStats(artifactCount, totalSize, lastUpdated.equals(Instant.MIN) ? null : lastUpdated);
	}

	private String normalizePath(String path) {
		if (path == null || path.trim().isEmpty() || path.equals("/")) {
			return "";
		}
		// Remove leading/trailing slashes and normalize
		return path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
	}

	private String getParentPath(String path) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash <= 0) {
			return "";
		}
		return path.substring(0, lastSlash);
	}

	private String determineContentType(String fileName) {
		if (fileName.endsWith(".jar")) {
			return "application/java-archive";
		}
		else if (fileName.endsWith(".pom") || fileName.endsWith(".xml")) {
			return "application/xml";
		}
		else if (fileName.endsWith(".sha1") || fileName.endsWith(".md5") || fileName.endsWith(".sha256")
				|| fileName.endsWith(".sha512")) {
			return "text/plain";
		}
		else if (fileName.endsWith(".asc")) {
			return "application/pgp-signature";
		}
		else {
			return "application/octet-stream";
		}
	}

	// Response DTOs
	public record RepositoryInfo(String id, String url, long artifactCount, long totalSize, Instant lastUpdated) {
	}

	public record BrowseResult(String repositoryId, String currentPath, String parentPath,
			List<RepositoryEntry> entries) {
	}

	public record RepositoryEntry(String name, String type, String path,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long size, Instant lastModified) {
	}

	public record FileInfo(String repositoryId, String path, String name, String type, long size, Instant lastModified,
			String contentType, @JsonInclude(JsonInclude.Include.NON_NULL) String sha1,
			@JsonInclude(JsonInclude.Include.NON_NULL) String sha256) {
	}

	private record RepositoryStats(long artifactCount, long totalSize, Instant lastUpdated) {
	}

}