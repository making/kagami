package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageEntry;
import am.ik.kagami.storage.StorageService;
import am.ik.kagami.storage.StorageStats;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * Service for browsing repository contents
 */
@Service
public class RepositoryService {

	private static final Logger logger = LoggerFactory.getLogger(RepositoryService.class);

	private final StorageService storageService;

	private final KagamiProperties properties;

	private final AsyncTaskExecutor taskExecutor;

	public RepositoryService(StorageService storageService, KagamiProperties properties,
			AsyncTaskExecutor taskExecutor) {
		this.storageService = storageService;
		this.properties = properties;
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Get all configured repositories without touching the storage.
	 * @return list of repository summaries sorted by priority (higher first), keeping the
	 * configuration order among repositories of the same priority
	 */
	public List<RepositorySummary> getRepositories() {
		return this.properties.repositories()
			.entrySet()
			.stream()
			.sorted(Map.Entry.<String, KagamiProperties.Repository>comparingByValue())
			.map(entry -> RepositorySummary.builder()
				.id(entry.getKey())
				.url(entry.getValue().url())
				.isPrivate(entry.getValue().isPrivate())
				.priority(entry.getValue().priority())
				.build())
			.toList();
	}

	/**
	 * Find a configured repository without touching the storage.
	 * @param repositoryId the repository identifier
	 * @return the repository summary, empty if it is not configured
	 */
	public Optional<RepositorySummary> findRepository(String repositoryId) {
		return Optional.ofNullable(this.properties.repositories().get(repositoryId))
			.map(repository -> RepositorySummary.builder()
				.id(repositoryId)
				.url(repository.url())
				.isPrivate(repository.isPrivate())
				.priority(repository.priority())
				.build());
	}

	/**
	 * Get all configured repositories with their statistics. Computing the statistics
	 * visits every stored file, so the repositories are processed concurrently.
	 * @return list of repository information in configuration order
	 */
	public List<RepositoryInfo> getRepositoryStats() {
		List<RepositorySummary> repositories = getRepositories();
		List<CompletableFuture<StorageStats>> stats = repositories.stream()
			.map(repository -> CompletableFuture.supplyAsync(() -> stats(repository.id()), this.taskExecutor))
			.toList();
		List<RepositoryInfo> infos = new ArrayList<>(repositories.size());
		for (int i = 0; i < repositories.size(); i++) {
			RepositorySummary repository = repositories.get(i);
			StorageStats stat = stats.get(i).join();
			infos.add(RepositoryInfo.builder()
				.id(repository.id())
				.url(repository.url())
				.artifactCount(stat.artifactCount())
				.totalSize(stat.totalSize())
				.lastUpdated(stat.lastUpdated())
				.isPrivate(repository.isPrivate())
				.build());
		}
		return infos;
	}

	private StorageStats stats(String repositoryId) {
		try {
			return this.storageService.stats(repositoryId);
		}
		catch (IOException | RuntimeException e) {
			logger.warn("Failed to calculate statistics of repository {}", repositoryId, e);
			return StorageStats.EMPTY;
		}
	}

	/**
	 * Browse repository contents at the specified path
	 * @param repositoryId the repository identifier
	 * @param path the path within the repository (null or empty for root)
	 * @return browse result with entries
	 */
	public BrowseResult browseRepository(String repositoryId, @Nullable String path) throws IOException {
		ArtifactLocation location = toLocation(repositoryId, path);
		List<RepositoryEntry> entries = this.storageService.list(location)
			.stream()
			.map(RepositoryService::toRepositoryEntry)
			.toList();
		return BrowseResult.builder()
			.repositoryId(repositoryId)
			.currentPath(location.artifactPath())
			.parentPath(getParentPath(location.artifactPath()))
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
		ArtifactLocation location = toLocation(repositoryId, path);
		if (location.isRoot()) {
			throw new IllegalArgumentException("Path is required");
		}
		StorageEntry entry = this.storageService.stat(location)
			.filter(StorageEntry::isFile)
			.orElseThrow(() -> new IllegalArgumentException("File not found: " + path));
		String fileName = entry.name();
		return FileInfo.builder()
			.repositoryId(repositoryId)
			.path(entry.path())
			.name(fileName)
			.type("file")
			.size(Objects.requireNonNullElse(entry.size(), 0L))
			.lastModified(Objects.requireNonNull(entry.lastModified(), "lastModified is required for a file"))
			.contentType(ArtifactContentType.of(fileName))
			.sha1(readChecksum(location.sibling(fileName + ".sha1")))
			.sha256(readChecksum(location.sibling(fileName + ".sha256")))
			.build();
	}

	private ArtifactLocation toLocation(String repositoryId, @Nullable String path) {
		if (!this.properties.repositories().containsKey(repositoryId)) {
			throw new IllegalArgumentException("Repository not found: " + repositoryId);
		}
		return new ArtifactLocation(repositoryId, normalizePath(path));
	}

	private @Nullable String readChecksum(ArtifactLocation location) throws IOException {
		Optional<Resource> resource = this.storageService.retrieve(location);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream inputStream = resource.get().getInputStream()) {
			return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8).trim();
		}
	}

	private static RepositoryEntry toRepositoryEntry(StorageEntry entry) {
		return RepositoryEntry.builder()
			.name(entry.name())
			.type(entry.isDirectory() ? "directory" : "file")
			.path(entry.path())
			.size(entry.size())
			.lastModified(entry.lastModified())
			.build();
	}

	private static String normalizePath(@Nullable String path) {
		if (path == null || path.trim().isEmpty() || path.equals("/")) {
			return "";
		}
		// Remove leading/trailing slashes and normalize
		return path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
	}

	private static @Nullable String getParentPath(String path) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash <= 0) {
			return "";
		}
		return path.substring(0, lastSlash);
	}

	// Response DTOs

	/**
	 * A configured repository, known without touching the storage.
	 */
	public record RepositorySummary(String id, String url, boolean isPrivate,
			int priority) implements Comparable<RepositorySummary> {

		/**
		 * The natural ordering matches {@link KagamiProperties.Repository}: sorts by
		 * priority, higher first.
		 */
		@Override
		public int compareTo(RepositorySummary other) {
			return Integer.compare(other.priority, this.priority);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String id;

			@Nullable private String url;

			private boolean isPrivate;

			private int priority;

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

			public Builder isPrivate(boolean isPrivate) {
				this.isPrivate = isPrivate;
				return this;
			}

			public Builder priority(int priority) {
				this.priority = priority;
				return this;
			}

			public RepositorySummary build() {
				return new RepositorySummary(Objects.requireNonNull(this.id, "id is required"),
						Objects.requireNonNull(this.url, "url is required"), this.isPrivate, this.priority);
			}

		}
	}

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

	/**
	 * A file or directory entry. {@code lastModified} is absent for directories on
	 * backends that do not track a directory timestamp (object storage).
	 */
	public record RepositoryEntry(String name, String type, String path,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long size,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Instant lastModified) {

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

			public Builder lastModified(@Nullable Instant lastModified) {
				this.lastModified = lastModified;
				return this;
			}

			public RepositoryEntry build() {
				return new RepositoryEntry(Objects.requireNonNull(this.name, "name is required"),
						Objects.requireNonNull(this.type, "type is required"),
						Objects.requireNonNull(this.path, "path is required"), this.size, this.lastModified);
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

}
