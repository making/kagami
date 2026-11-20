package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.Retention;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageEntry;
import am.ik.kagami.storage.StorageService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Removes mirrored artifacts according to the per-repository
 * {@code kagami.repositories.<id>.retention} policy.
 * <p>
 * This is separate from {@link RepositoryGarbageCollector}: the collector removes
 * directories that hold nothing but bookkeeping files, this service removes artifacts
 * that outlived the configured age or that make the repository exceed the configured
 * size. When no limit is configured the repository is kept as it is.
 * <p>
 * The eviction unit is an artifact together with its sidecar files (checksums and
 * sigstore bundles). A sidecar is never removed without the artifact it belongs to. The
 * rules describe the files, not the repository format, so they apply to Maven, npm and
 * PyPI repositories alike. Eviction order is oldest {@code lastModified} first.
 * <p>
 * The metadata cache is out of scope: mutable indexes are bounded separately by
 * {@code kagami.metadata-cache.*}.
 */
@Service
public class RepositoryRetentionService {

	private static final Logger logger = LoggerFactory.getLogger(RepositoryRetentionService.class);

	private static final String RESOLVER_STATUS_FILE = "resolver-status.properties";

	private static final String REMOTE_REPOSITORIES_FILE = "_remote.repositories";

	private static final String LAST_UPDATED_SUFFIX = ".lastUpdated";

	private static final Pattern MAVEN_METADATA_FILE = Pattern
		.compile("maven-metadata[^/]*\\.xml(?:\\.(?:sha1|sha256|sha512|md5|asc))?");

	private static final List<String> CHECKSUM_SUFFIXES = List.of(".sha1", ".sha256", ".sha512", ".md5", ".asc");

	private final KagamiProperties properties;

	private final StorageService storageService;

	private final InstantSource instantSource;

	private final MeterRegistry meterRegistry;

	private final Map<String, AtomicLong> repositorySizes = new ConcurrentHashMap<>();

	private final Map<String, Counter> evictedCounters = new ConcurrentHashMap<>();

	private final Map<String, Counter> evictedBytesCounters = new ConcurrentHashMap<>();

	public RepositoryRetentionService(KagamiProperties properties, StorageService storageService,
			InstantSource instantSource, MeterRegistry meterRegistry) {
		this.properties = properties;
		this.storageService = storageService;
		this.instantSource = instantSource;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Whether the repository configures at least one retention limit.
	 * @param repositoryId the repository identifier
	 * @return {@code true} if an age or size limit is set
	 */
	public boolean isEnabled(String repositoryId) {
		return retention(repositoryId).isPresent();
	}

	/**
	 * The configured retention policy of a repository.
	 * @param repositoryId the repository identifier
	 * @return the policy, empty if the repository is unknown or has no limit
	 */
	public Optional<Retention> retention(String repositoryId) {
		KagamiProperties.Repository repository = this.properties.repositories().get(repositoryId);
		if (repository == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(repository.retention()).filter(Retention::enabled);
	}

	/**
	 * Apply the configured retention policy to one repository.
	 * @param repositoryId the repository identifier
	 * @return the evicted artifacts and per-artifact failures, or an empty result when
	 * the repository has no retention policy
	 * @throws IOException if an I/O error occurs while listing storage
	 */
	public RetentionResult apply(String repositoryId) throws IOException {
		Retention retention = retention(repositoryId).orElse(null);
		if (retention == null) {
			return RetentionResult.skipped(repositoryId);
		}
		Set<String> bundleSuffixes = bundleSuffixes(repositoryId);
		Scan scan = scan(repositoryId, bundleSuffixes);
		List<Candidate> selected = select(scan, retention, now());
		List<String> evictedPaths = new ArrayList<>();
		List<RetentionFailure> failures = new ArrayList<>();
		long evictedBytes = 0;
		for (Candidate candidate : selected) {
			try {
				long bytes = evict(repositoryId, candidate);
				if (bytes < 0) {
					// A member changed after the scan: a concurrent write won the race
					continue;
				}
				evictedBytes += bytes;
				evictedPaths.add(candidate.path());
				logger.info("Evicted artifact {} from repository {}", candidate.path(), repositoryId);
			}
			catch (IOException e) {
				String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				failures.add(new RetentionFailure(candidate.path(), message));
				logger.warn("Failed to evict artifact {} from repository {}", candidate.path(), repositoryId, e);
			}
		}
		long remainingSize = Math.max(0, scan.totalSize() - evictedBytes);
		recordMetrics(repositoryId, evictedPaths.size(), evictedBytes, remainingSize);
		return RetentionResult.builder()
			.repositoryId(repositoryId)
			.evictedPaths(evictedPaths)
			.failures(failures)
			.remainingSize(remainingSize)
			.build();
	}

	/**
	 * Apply the configured retention policy to every repository that sets one. A failing
	 * repository does not stop the others.
	 * @return the result of every repository that has a policy
	 */
	public List<RetentionResult> applyAll() {
		List<RetentionResult> results = new ArrayList<>();
		for (String repositoryId : this.properties.repositories().keySet()) {
			if (!isEnabled(repositoryId)) {
				continue;
			}
			try {
				results.add(apply(repositoryId));
			}
			catch (IOException | RuntimeException e) {
				logger.warn("Failed to apply the retention policy to repository {}", repositoryId, e);
			}
		}
		return List.copyOf(results);
	}

	private Scan scan(String repositoryId, Set<String> bundleSuffixes) throws IOException {
		List<FileRef> files = new ArrayList<>();
		collectFiles(ArtifactLocation.root(repositoryId), files);
		long totalSize = files.stream().mapToLong(FileRef::size).sum();
		Map<String, Map<String, FileRef>> byDirectory = new HashMap<>();
		for (FileRef file : files) {
			byDirectory.computeIfAbsent(parentPath(file.path()), key -> new HashMap<>()).put(file.name(), file);
		}
		Set<String> sidecarSuffixes = sidecarSuffixes(bundleSuffixes);
		List<Candidate> candidates = new ArrayList<>();
		for (Map<String, FileRef> directory : byDirectory.values()) {
			for (FileRef file : directory.values()) {
				if (isBookkeeping(file.name()) || isSidecarOfAnotherFile(file, directory, sidecarSuffixes)) {
					continue;
				}
				candidates.add(candidate(file, directory, sidecarSuffixes));
			}
		}
		return new Scan(totalSize, candidates);
	}

	private void collectFiles(ArtifactLocation directory, List<FileRef> files) throws IOException {
		for (StorageEntry entry : this.storageService.list(directory)) {
			if (entry.isDirectory()) {
				collectFiles(new ArtifactLocation(directory.repositoryId(), entry.path()), files);
			}
			else if (entry.isFile()) {
				files.add(new FileRef(entry.name(), entry.path(), Objects.requireNonNullElse(entry.size(), 0L),
						entry.lastModified()));
			}
		}
	}

	private Candidate candidate(FileRef artifact, Map<String, FileRef> directory, Set<String> sidecarSuffixes) {
		List<Member> members = new ArrayList<>();
		members.add(new Member(artifact.path(), artifact.lastModified(), artifact.size()));
		long size = artifact.size();
		Instant lastModified = artifact.lastModified();
		for (FileRef other : directory.values()) {
			if (artifact.name().equals(other.name())) {
				continue;
			}
			if (!artifact.name().equals(baseArtifactName(other.name(), sidecarSuffixes))) {
				continue;
			}
			members.add(new Member(other.path(), other.lastModified(), other.size()));
			size += other.size();
			if (other.lastModified() != null && (lastModified == null || other.lastModified().isAfter(lastModified))) {
				lastModified = other.lastModified();
			}
		}
		return new Candidate(artifact.path(), List.copyOf(members), size, lastModified);
	}

	private static boolean isSidecarOfAnotherFile(FileRef file, Map<String, FileRef> directory,
			Set<String> sidecarSuffixes) {
		String base = baseArtifactName(file.name(), sidecarSuffixes);
		return base != null && directory.containsKey(base);
	}

	/**
	 * The artifact name a sidecar belongs to, or {@code null} when the name does not end
	 * with a sidecar suffix. Suffixes are stripped repeatedly so that a checksum of a
	 * signature ({@code lib.jar.asc.sha1}) belongs to the artifact as well.
	 */
	private static @Nullable String baseArtifactName(String name, Set<String> sidecarSuffixes) {
		String current = name;
		boolean stripped = false;
		while (true) {
			String matched = null;
			for (String suffix : sidecarSuffixes) {
				if (current.length() > suffix.length() && current.endsWith(suffix)) {
					matched = suffix;
					break;
				}
			}
			if (matched == null) {
				break;
			}
			current = current.substring(0, current.length() - matched.length());
			stripped = true;
		}
		return stripped ? current : null;
	}

	private static Set<String> sidecarSuffixes(Set<String> bundleSuffixes) {
		List<String> suffixes = new ArrayList<>(CHECKSUM_SUFFIXES);
		suffixes.addAll(bundleSuffixes);
		// Longest first: ".attestation.sigstore.json" must win over ".sigstore.json"
		suffixes.sort(Comparator.comparingInt(String::length).reversed());
		return new LinkedHashSet<>(suffixes);
	}

	private Set<String> bundleSuffixes(String repositoryId) {
		KagamiProperties.Repository repository = this.properties.repositories().get(repositoryId);
		if (repository == null || repository.sigstore() == null) {
			return Set.of();
		}
		List<String> suffixes = repository.sigstore().bundleSuffixes();
		if (suffixes == null) {
			return Set.of();
		}
		Set<String> prefixed = new HashSet<>();
		for (String suffix : suffixes) {
			if (suffix != null && !suffix.isBlank()) {
				prefixed.add(suffix.startsWith(".") ? suffix : "." + suffix);
			}
		}
		return prefixed;
	}

	private List<Candidate> select(Scan scan, Retention retention, Instant now) {
		List<Candidate> sorted = scan.candidates()
			.stream()
			.sorted(Comparator.comparing(Candidate::lastModified, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(Candidate::path))
			.toList();
		Set<String> selected = new LinkedHashSet<>();
		long selectedSize = 0;
		if (retention.maxAge() != null) {
			Instant cutoff = cutoff(now, retention.maxAge());
			for (Candidate candidate : sorted) {
				if (candidate.lastModified() != null && candidate.lastModified().isBefore(cutoff)) {
					selected.add(candidate.path());
					selectedSize += candidate.size();
				}
			}
		}
		if (retention.maxSize() != null) {
			long remaining = scan.totalSize() - selectedSize;
			long limit = retention.maxSize().toBytes();
			for (Candidate candidate : sorted) {
				if (remaining <= limit) {
					break;
				}
				if (selected.contains(candidate.path())) {
					continue;
				}
				selected.add(candidate.path());
				remaining -= candidate.size();
			}
		}
		return sorted.stream().filter(candidate -> selected.contains(candidate.path())).toList();
	}

	private static Instant cutoff(Instant now, Duration maxAge) {
		try {
			return now.minus(maxAge);
		}
		catch (DateTimeException | ArithmeticException e) {
			return Instant.MIN;
		}
	}

	/**
	 * Delete the artifact and its sidecars.
	 * @return the number of bytes removed, or {@code -1} when a member changed after the
	 * scan and the candidate was left untouched
	 */
	private long evict(String repositoryId, Candidate candidate) throws IOException {
		for (Member member : candidate.members()) {
			ArtifactLocation location = new ArtifactLocation(repositoryId, member.path());
			Optional<StorageEntry> current = this.storageService.stat(location);
			if (current.isEmpty()) {
				continue;
			}
			if (!current.get().isFile()) {
				return -1;
			}
			Instant lastModified = current.get().lastModified();
			if (member.lastModified() != null && lastModified != null && !member.lastModified().equals(lastModified)) {
				return -1;
			}
		}
		long deleted = 0;
		IOException failure = null;
		for (Member member : candidate.members()) {
			ArtifactLocation location = new ArtifactLocation(repositoryId, member.path());
			try {
				if (this.storageService.deleteFile(location)) {
					deleted += member.size();
				}
			}
			catch (IOException e) {
				if (failure == null) {
					failure = e;
				}
				else {
					failure.addSuppressed(e);
				}
			}
		}
		pruneEmptyAncestors(repositoryId, candidate.path());
		if (failure != null) {
			throw failure;
		}
		return deleted;
	}

	private void pruneEmptyAncestors(String repositoryId, String artifactPath) {
		String parent = parentPath(artifactPath);
		while (!parent.isEmpty()) {
			ArtifactLocation directory = new ArtifactLocation(repositoryId, parent);
			try {
				if (!this.storageService.list(directory).isEmpty() || !this.storageService.deleteIfEmpty(directory)) {
					return;
				}
			}
			catch (IOException e) {
				logger.debug("Failed to prune empty directory {}/{}", repositoryId, parent, e);
				return;
			}
			parent = parentPath(parent);
		}
	}

	private void recordMetrics(String repositoryId, int evicted, long evictedBytes, long remainingSize) {
		this.evictedCounters
			.computeIfAbsent(repositoryId,
					key -> Counter.builder("kagami.retention.evicted")
						.description("Artifacts removed by the retention policy")
						.tag("repository", key)
						.register(this.meterRegistry))
			.increment(evicted);
		this.evictedBytesCounters
			.computeIfAbsent(repositoryId,
					key -> Counter.builder("kagami.retention.evicted.bytes")
						.description("Bytes removed by the retention policy")
						.tag("repository", key)
						.register(this.meterRegistry))
			.increment(evictedBytes);
		AtomicLong holder = this.repositorySizes.computeIfAbsent(repositoryId, key -> {
			AtomicLong value = new AtomicLong();
			Gauge.builder("kagami.retention.size", value, AtomicLong::get)
				.description("Repository size in bytes after the last retention sweep")
				.tag("repository", key)
				.register(this.meterRegistry);
			return value;
		});
		holder.set(remainingSize);
	}

	private Instant now() {
		return this.instantSource.instant();
	}

	private static boolean isBookkeeping(String name) {
		return MAVEN_METADATA_FILE.matcher(name).matches() || RESOLVER_STATUS_FILE.equals(name)
				|| REMOTE_REPOSITORIES_FILE.equals(name) || name.endsWith(LAST_UPDATED_SUFFIX);
	}

	private static String parentPath(String path) {
		int lastSlash = path.lastIndexOf('/');
		return lastSlash < 0 ? "" : path.substring(0, lastSlash);
	}

	/**
	 * A stored file found by the scan.
	 */
	private record FileRef(String name, String path, long size, @Nullable Instant lastModified) {
	}

	/**
	 * A file that belongs to an artifact, with the timestamp it had during the scan.
	 */
	private record Member(String path, @Nullable Instant lastModified, long size) {
	}

	/**
	 * An artifact and the sidecar files that belong to it.
	 *
	 * @param path the repository-relative path of the artifact
	 * @param members the artifact and every sidecar to remove with it
	 * @param size the total size of the members in bytes
	 * @param lastModified the newest modification timestamp among the members
	 */
	private record Candidate(String path, List<Member> members, long size, @Nullable Instant lastModified) {
	}

	private record Scan(long totalSize, List<Candidate> candidates) {
	}

	/**
	 * The result of applying a retention policy to one repository.
	 *
	 * @param repositoryId the repository identifier
	 * @param evictedPaths the repository-relative paths of the evicted artifacts
	 * @param failures artifacts that could not be evicted and their error messages
	 * @param remainingSize the repository size in bytes after the eviction
	 */
	public record RetentionResult(String repositoryId, List<String> evictedPaths, List<RetentionFailure> failures,
			long remainingSize) {

		public static Builder builder() {
			return new Builder();
		}

		/**
		 * An empty result for a repository that has no retention policy.
		 * @param repositoryId the repository identifier
		 * @return the result
		 */
		public static RetentionResult skipped(String repositoryId) {
			return new RetentionResult(repositoryId, List.of(), List.of(), 0);
		}

		public static final class Builder {

			@Nullable private String repositoryId;

			private List<String> evictedPaths = List.of();

			private List<RetentionFailure> failures = List.of();

			private long remainingSize;

			private Builder() {
			}

			public Builder repositoryId(String repositoryId) {
				this.repositoryId = repositoryId;
				return this;
			}

			public Builder evictedPaths(List<String> evictedPaths) {
				this.evictedPaths = evictedPaths;
				return this;
			}

			public Builder failures(List<RetentionFailure> failures) {
				this.failures = failures;
				return this;
			}

			public Builder remainingSize(long remainingSize) {
				this.remainingSize = remainingSize;
				return this;
			}

			public RetentionResult build() {
				return new RetentionResult(Objects.requireNonNull(this.repositoryId, "repositoryId is required"),
						List.copyOf(this.evictedPaths), List.copyOf(this.failures), this.remainingSize);
			}

		}

	}

	/**
	 * An artifact that could not be evicted.
	 *
	 * @param path the repository-relative path of the artifact
	 * @param message the storage error message
	 */
	public record RetentionFailure(String path, String message) {
	}

}
