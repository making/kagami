package am.ik.kagami.repository;

import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageEntry;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds and removes cache directories that hold nothing but bookkeeping files, together
 * with empty directories left behind after a delete or a failed fetch.
 * <p>
 * A candidate is a repository directory whose direct children are all cache bookkeeping
 * files ({@code maven-metadata*.xml} and their checksums,
 * {@code resolver-status.properties}, {@code _remote.repositories},
 * {@code *.lastUpdated}), or an empty directory. The children, or the directory itself
 * for an empty one, must be at least the requested age old. The rules describe what is
 * left behind, not the repository format, so they apply to every format equally. Files
 * are removed individually after a second eligibility check, so a request that adds an
 * artifact concurrently cannot make the collector remove that artifact.
 */
@Service
public class RepositoryGarbageCollector {

	private static final Logger logger = LoggerFactory.getLogger(RepositoryGarbageCollector.class);

	private static final String RESOLVER_STATUS_FILE = "resolver-status.properties";

	private static final String REMOTE_REPOSITORIES_FILE = "_remote.repositories";

	private static final String LAST_UPDATED_SUFFIX = ".lastUpdated";

	private static final Pattern MAVEN_METADATA_FILE = Pattern
		.compile("maven-metadata[^/]*\\.xml(?:\\.(?:sha1|sha256|sha512|md5|asc))?");

	private final StorageService storageService;

	private final InstantSource instantSource;

	public RepositoryGarbageCollector(StorageService storageService, InstantSource instantSource) {
		this.storageService = storageService;
		this.instantSource = instantSource;
	}

	/**
	 * Find eligible directories without changing storage.
	 * @param repositoryId the repository to inspect
	 * @param olderThan the minimum age of a candidate and of every child it contains;
	 * must not be negative
	 * @return the matching directories, sorted by repository-relative path
	 * @throws IOException if an I/O error occurs while listing storage
	 */
	public List<GarbageDirectory> findCandidates(String repositoryId, Duration olderThan) throws IOException {
		Instant cutoff = cutoff(olderThan);
		List<GarbageDirectory> candidates = new ArrayList<>();
		findCandidates(ArtifactLocation.root(repositoryId), cutoff, candidates);
		candidates.sort(Comparator.comparing(GarbageDirectory::path));
		return List.copyOf(candidates);
	}

	/**
	 * Remove eligible directories that pass the age check.
	 * @param repositoryId the repository to inspect
	 * @param olderThan the minimum age of a candidate and of every child it contains;
	 * must not be negative
	 * @return the directories whose eligible files were collected and any per-directory
	 * failures
	 * @throws IOException if an I/O error occurs while listing storage
	 */
	public GarbageCollectionResult collect(String repositoryId, Duration olderThan) throws IOException {
		Instant cutoff = cutoff(olderThan);
		List<GarbageDirectory> candidates = findCandidates(repositoryId, olderThan);
		List<String> collectedPaths = new ArrayList<>();
		List<GarbageCollectionFailure> failures = new ArrayList<>();
		for (GarbageDirectory candidate : candidates) {
			ArtifactLocation directory = new ArtifactLocation(repositoryId, candidate.path());
			try {
				// Re-check immediately before deleting. This protects against a cache
				// bookkeeping refresh or an artifact download that completed after the
				// initial walk.
				Optional<EligibleDirectory> eligible = garbageDirectory(directory, cutoff);
				if (eligible.isEmpty()) {
					continue;
				}
				collectFiles(directory, eligible.get().fileNames());
				// Local storage has real directory entries; object storage disappears
				// automatically after its last object is deleted. Directory cleanup is
				// best effort: the eligible files are already gone at this point.
				try {
					this.storageService.deleteIfEmpty(directory);
					pruneEmptyAncestors(directory);
				}
				catch (IOException e) {
					logger.warn("Failed to prune empty ancestors for {}/{}", repositoryId, candidate.path(), e);
				}
				collectedPaths.add(candidate.path());
				logger.info("Collected garbage directory {}/{}", repositoryId, candidate.path());
			}
			catch (IOException e) {
				String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				failures.add(new GarbageCollectionFailure(candidate.path(), message));
				logger.warn("Failed to collect garbage directory {}/{}", repositoryId, candidate.path(), e);
			}
		}
		return new GarbageCollectionResult(List.copyOf(collectedPaths), List.copyOf(failures));
	}

	private void findCandidates(ArtifactLocation directory, Instant cutoff, List<GarbageDirectory> candidates)
			throws IOException {
		for (StorageEntry entry : this.storageService.list(directory)) {
			if (!entry.isDirectory()) {
				continue;
			}
			ArtifactLocation child = new ArtifactLocation(directory.repositoryId(), entry.path());
			Optional<EligibleDirectory> candidate = garbageDirectory(child, cutoff);
			if (candidate.isPresent()) {
				candidates.add(candidate.get().directory());
			}
			else {
				findCandidates(child, cutoff, candidates);
			}
		}
	}

	private Optional<EligibleDirectory> garbageDirectory(ArtifactLocation directory, Instant cutoff)
			throws IOException {
		List<StorageEntry> children = this.storageService.list(directory);
		if (children.isEmpty()) {
			return emptyDirectory(directory, cutoff);
		}
		List<String> eligibleFileNames = new ArrayList<>(children.size());
		List<Instant> modifiedTimes = new ArrayList<>(children.size());
		for (StorageEntry child : children) {
			Instant modified = child.lastModified();
			if (!child.isFile() || !isBookkeepingFile(child.name()) || modified == null || modified.isAfter(cutoff)) {
				return Optional.empty();
			}
			eligibleFileNames.add(child.name());
			modifiedTimes.add(modified);
		}
		return Optional.of(new EligibleDirectory(
				new GarbageDirectory(directory.artifactPath(),
						modifiedTimes.stream().max(Comparator.naturalOrder()).orElseThrow()),
				eligibleFileNames.stream().sorted().toList()));
	}

	/**
	 * An empty directory is a candidate when storage keeps directory entries of its own.
	 * Object storage has none: a prefix disappears with its last object, so it never
	 * shows up as an empty directory in the first place.
	 */
	private Optional<EligibleDirectory> emptyDirectory(ArtifactLocation directory, Instant cutoff) throws IOException {
		Optional<StorageEntry> found = this.storageService.stat(directory);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		StorageEntry entry = found.get();
		Instant modified = entry.lastModified();
		if (!entry.isDirectory() || modified == null || modified.isAfter(cutoff)) {
			return Optional.empty();
		}
		return Optional.of(new EligibleDirectory(new GarbageDirectory(directory.artifactPath(), modified), List.of()));
	}

	private static boolean isBookkeepingFile(String name) {
		return MAVEN_METADATA_FILE.matcher(name).matches() || RESOLVER_STATUS_FILE.equals(name)
				|| REMOTE_REPOSITORIES_FILE.equals(name) || name.endsWith(LAST_UPDATED_SUFFIX);
	}

	private void collectFiles(ArtifactLocation directory, List<String> fileNames) throws IOException {
		IOException failure = null;
		for (String fileName : fileNames) {
			try {
				this.storageService.deleteFile(directory.resolve(fileName));
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
		if (failure != null) {
			throw failure;
		}
	}

	private void pruneEmptyAncestors(ArtifactLocation directory) throws IOException {
		ArtifactLocation parent = directory;
		while (!parent.isRoot()) {
			int lastSlash = parent.artifactPath().lastIndexOf('/');
			if (lastSlash < 0) {
				return;
			}
			parent = new ArtifactLocation(parent.repositoryId(), parent.artifactPath().substring(0, lastSlash));
			if (!this.storageService.list(parent).isEmpty() || !this.storageService.deleteIfEmpty(parent)) {
				return;
			}
		}
	}

	private Instant cutoff(Duration olderThan) {
		Objects.requireNonNull(olderThan, "olderThan is required");
		if (olderThan.isNegative()) {
			throw new IllegalArgumentException("olderThan must not be negative");
		}
		try {
			return this.instantSource.instant().minus(olderThan);
		}
		catch (DateTimeException | ArithmeticException e) {
			throw new IllegalArgumentException("olderThan is too large", e);
		}
	}

	/**
	 * A directory that is eligible for collection.
	 *
	 * @param path the path relative to the repository root
	 * @param lastModified the newest modification timestamp of the directory or its
	 * bookkeeping files
	 */
	public record GarbageDirectory(String path, Instant lastModified) {
	}

	/**
	 * The result of collecting eligible directories.
	 *
	 * @param collectedPaths repository-relative paths whose eligible files were removed
	 * @param failures paths that could not be collected and their error messages
	 */
	public record GarbageCollectionResult(List<String> collectedPaths, List<GarbageCollectionFailure> failures) {
	}

	/**
	 * A failure to collect one eligible directory.
	 *
	 * @param path the path relative to the repository root
	 * @param message the storage error message
	 */
	public record GarbageCollectionFailure(String path, String message) {
	}

	private record EligibleDirectory(GarbageDirectory directory, List<String> fileNames) {
	}

}
