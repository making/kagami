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
import java.util.Objects;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
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
				KagamiProperties.Repository repository = entry.getValue();
				String url = repository.url();
				boolean isPrivate = repository.isPrivate();

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

				repositories.add(RepositoryInfo.builder()
					.id(repoId)
					.url(url)
					.artifactCount(artifactCount)
					.totalSize(totalSize)
					.lastUpdated(lastUpdated)
					.isPrivate(isPrivate)
					.build());
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
	public BrowseResult browseRepository(String repositoryId, @Nullable String path) throws IOException {
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
			return BrowseResult.builder()
				.repositoryId(repositoryId)
				.currentPath(normalizedPath)
				.parentPath(getParentPath(normalizedPath))
				.entries(List.of())
				.build();
		}

		// List directory contents
		List<RepositoryEntry> entries = new ArrayList<>();
		try (Stream<Path> stream = Files.list(targetPath)) {
			stream.sorted(Comparator.comparing(Path::getFileName)).forEach(entryPath -> {
				try {
					RepositoryEntry entry = createRepositoryEntry(repoRoot, entryPath);
					entries.add(entry);
				}
				catch (IOException e) {
					// Skip entries that can't be read
				}
			});
		}

		return BrowseResult.builder()
			.repositoryId(repositoryId)
			.currentPath(normalizedPath)
			.parentPath(getParentPath(normalizedPath))
			.entries(entries)
			.build();
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

		return FileInfo.builder()
			.repositoryId(repositoryId)
			.path(normalizedPath)
			.name(fileName)
			.type("file")
			.size(size)
			.lastModified(lastModified)
			.contentType(contentType)
			.sha1(sha1)
			.sha256(sha256)
			.build();
	}

	private RepositoryEntry createRepositoryEntry(Path repoRoot, Path entryPath) throws IOException {
		String relativePath = repoRoot.relativize(entryPath).toString().replace('\\', '/');
		String name = entryPath.getFileName().toString();
		String type = Files.isDirectory(entryPath) ? "directory" : "file";
		Instant lastModified = Files.getLastModifiedTime(entryPath).toInstant();

		RepositoryEntry.Builder builder = RepositoryEntry.builder()
			.name(name)
			.type(type)
			.path(relativePath)
			.lastModified(lastModified);
		if ("file".equals(type)) {
			builder.size(Files.size(entryPath));
		}
		return builder.build();
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

		return RepositoryStats.builder()
			.artifactCount(artifactCount)
			.totalSize(totalSize)
			.lastUpdated(lastUpdated.equals(Instant.MIN) ? null : lastUpdated)
			.build();
	}

	private String normalizePath(@Nullable String path) {
		if (path == null || path.trim().isEmpty() || path.equals("/")) {
			return "";
		}
		// Remove leading/trailing slashes and normalize
		return path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
	}

	private @Nullable String getParentPath(String path) {
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
	public record RepositoryInfo(String id, String url, long artifactCount, long totalSize,
			@Nullable Instant lastUpdated, boolean isPrivate) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String id;

			@Nullable private String url;

			private long artifactCount;

			private long totalSize;

			@Nullable private Instant lastUpdated;

			private boolean isPrivate;

			private Builder() {
			}

			public Builder id(String id) {
				this.id = id;
				return this;
			}

			public Builder url(String url) {
				this.url = url;
				return this;
			}

			public Builder artifactCount(long artifactCount) {
				this.artifactCount = artifactCount;
				return this;
			}

			public Builder totalSize(long totalSize) {
				this.totalSize = totalSize;
				return this;
			}

			public Builder lastUpdated(@Nullable Instant lastUpdated) {
				this.lastUpdated = lastUpdated;
				return this;
			}

			public Builder isPrivate(boolean isPrivate) {
				this.isPrivate = isPrivate;
				return this;
			}

			public RepositoryInfo build() {
				return new RepositoryInfo(Objects.requireNonNull(this.id, "id is required"),
						Objects.requireNonNull(this.url, "url is required"), this.artifactCount, this.totalSize,
						this.lastUpdated, this.isPrivate);
			}

		}

	}

	public record BrowseResult(String repositoryId, String currentPath, @Nullable String parentPath,
			List<RepositoryEntry> entries) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String repositoryId;

			@Nullable private String currentPath;

			@Nullable private String parentPath;

			@Nullable private List<RepositoryEntry> entries;

			private Builder() {
			}

			public Builder repositoryId(String repositoryId) {
				this.repositoryId = repositoryId;
				return this;
			}

			public Builder currentPath(String currentPath) {
				this.currentPath = currentPath;
				return this;
			}

			public Builder parentPath(@Nullable String parentPath) {
				this.parentPath = parentPath;
				return this;
			}

			public Builder entries(List<RepositoryEntry> entries) {
				this.entries = entries;
				return this;
			}

			public BrowseResult build() {
				return new BrowseResult(Objects.requireNonNull(this.repositoryId, "repositoryId is required"),
						Objects.requireNonNull(this.currentPath, "currentPath is required"), this.parentPath,
						Objects.requireNonNull(this.entries, "entries is required"));
			}

		}

	}

	public record RepositoryEntry(String name, String type, String path,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long size, Instant lastModified) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String name;

			@Nullable private String type;

			@Nullable private String path;

			@Nullable private Long size;

			@Nullable private Instant lastModified;

			private Builder() {
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder type(String type) {
				this.type = type;
				return this;
			}

			public Builder path(String path) {
				this.path = path;
				return this;
			}

			public Builder size(@Nullable Long size) {
				this.size = size;
				return this;
			}

			public Builder lastModified(Instant lastModified) {
				this.lastModified = lastModified;
				return this;
			}

			public RepositoryEntry build() {
				return new RepositoryEntry(Objects.requireNonNull(this.name, "name is required"),
						Objects.requireNonNull(this.type, "type is required"),
						Objects.requireNonNull(this.path, "path is required"), this.size,
						Objects.requireNonNull(this.lastModified, "lastModified is required"));
			}

		}

	}

	public record FileInfo(String repositoryId, String path, String name, String type, long size, Instant lastModified,
			String contentType, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String sha1,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String sha256) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String repositoryId;

			@Nullable private String path;

			@Nullable private String name;

			@Nullable private String type;

			private long size;

			@Nullable private Instant lastModified;

			@Nullable private String contentType;

			@Nullable private String sha1;

			@Nullable private String sha256;

			private Builder() {
			}

			public Builder repositoryId(String repositoryId) {
				this.repositoryId = repositoryId;
				return this;
			}

			public Builder path(String path) {
				this.path = path;
				return this;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder type(String type) {
				this.type = type;
				return this;
			}

			public Builder size(long size) {
				this.size = size;
				return this;
			}

			public Builder lastModified(Instant lastModified) {
				this.lastModified = lastModified;
				return this;
			}

			public Builder contentType(String contentType) {
				this.contentType = contentType;
				return this;
			}

			public Builder sha1(@Nullable String sha1) {
				this.sha1 = sha1;
				return this;
			}

			public Builder sha256(@Nullable String sha256) {
				this.sha256 = sha256;
				return this;
			}

			public FileInfo build() {
				return new FileInfo(Objects.requireNonNull(this.repositoryId, "repositoryId is required"),
						Objects.requireNonNull(this.path, "path is required"),
						Objects.requireNonNull(this.name, "name is required"),
						Objects.requireNonNull(this.type, "type is required"), this.size,
						Objects.requireNonNull(this.lastModified, "lastModified is required"),
						Objects.requireNonNull(this.contentType, "contentType is required"), this.sha1, this.sha256);
			}

		}

	}

	private record RepositoryStats(long artifactCount, long totalSize, @Nullable Instant lastUpdated) {

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private long artifactCount;

			private long totalSize;

			@Nullable private Instant lastUpdated;

			private Builder() {
			}

			Builder artifactCount(long artifactCount) {
				this.artifactCount = artifactCount;
				return this;
			}

			Builder totalSize(long totalSize) {
				this.totalSize = totalSize;
				return this;
			}

			Builder lastUpdated(@Nullable Instant lastUpdated) {
				this.lastUpdated = lastUpdated;
				return this;
			}

			RepositoryStats build() {
				return new RepositoryStats(this.artifactCount, this.totalSize, this.lastUpdated);
			}

		}

	}

}