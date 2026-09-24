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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds and removes cache-bookkeeping-only directories left behind by failed Maven cache
 * requests.
 * <p>
 * A candidate is a repository directory whose direct children are exactly one of these
 * file sets: {@code maven-metadata.xml} and {@code maven-metadata.xml.sha1}, or
 * {@code resolver-status.properties}. The files must be at least the requested age old.
 * They are removed individually after a second eligibility check, so a request that adds
 * an artifact concurrently cannot make the collector remove that artifact.
 */
@Service
public class RepositoryGarbageCollector {

	private static final Logger logger = LoggerFactory.getLogger(RepositoryGarbageCollector.class);

	private static final String RESOLVER_STATUS_FILE = "resolver-status.properties";

	private static final List<Set<String>> ELIGIBLE_FILE_SETS = List
		.of(Set.of("maven-metadata.xml", "maven-metadata.xml.sha1"), Set.of(RESOLVER_STATUS_FILE));

	private final StorageService storageService;

	private final InstantSource instantSource;

	public RepositoryGarbageCollector(StorageService storageService, InstantSource instantSource) {
		this.storageService = storageService;
		this.instantSource = instantSource;
	}

	/**
	 * Find eligible cache-bookkeeping directories without changing storage.
	 * @param repositoryId the repository to inspect
	 * @param olderThan the minimum age of all files in a candidate; must not be negative
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
	 * Remove eligible cache-bookkeeping directories that pass the age check.
	 * @param repositoryId the repository to inspect
	 * @param olderThan the minimum age of all files in a candidate; must not be negative
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
				logger.info("Collected cache-bookkeeping-only directory {}/{}", repositoryId, candidate.path());
			}
			catch (IOException e) {
				String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				failures.add(new GarbageCollectionFailure(candidate.path(), message));
				logger.warn("Failed to collect cache-bookkeeping-only directory {}/{}", repositoryId, candidate.path(),
						e);
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
		List<String> eligibleFileNames = findEligibleFileNames(children);
		if (eligibleFileNames.isEmpty() || children.size() != eligibleFileNames.size()) {
			return Optional.empty();
		}
		List<Instant> modifiedTimes = new ArrayList<>(children.size());
		for (StorageEntry child : children) {
			Instant modified = child.lastModified();
			if (!child.isFile() || !eligibleFileNames.contains(child.name()) || modified == null
					|| modified.isAfter(cutoff)) {
				return Optional.empty();
			}
			modifiedTimes.add(modified);
		}
		return Optional.of(new EligibleDirectory(new GarbageDirectory(directory.artifactPath(),
				modifiedTimes.stream().max(Comparator.naturalOrder()).orElseThrow()), eligibleFileNames));
	}

	private static List<String> findEligibleFileNames(List<StorageEntry> children) {
		Set<String> names = new HashSet<>();
		for (StorageEntry child : children) {
			names.add(child.name());
		}
		for (Set<String> eligibleFiles : ELIGIBLE_FILE_SETS) {
			if (names.equals(eligibleFiles)) {
				return eligibleFiles.stream().sorted().toList();
			}
		}
		return List.of();
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
	 * A directory that contains only an eligible cache-bookkeeping file set.
	 *
	 * @param path the path relative to the repository root
	 * @param lastModified the newest file modification timestamp in the directory
	 */
	public record GarbageDirectory(String path, Instant lastModified) {
	}

	/**
	 * The result of collecting eligible cache-bookkeeping directories.
	 *
	 * @param collectedPaths repository-relative paths whose eligible files were removed
	 * @param failures paths that could not be collected and their error messages
	 */
	public record GarbageCollectionResult(List<String> collectedPaths, List<GarbageCollectionFailure> failures) {
	}

	/**
	 * A failure to collect one eligible cache-bookkeeping directory.
	 *
	 * @param path the path relative to the repository root
	 * @param message the storage error message
	 */
	public record GarbageCollectionFailure(String path, String message) {
	}

	private record EligibleDirectory(GarbageDirectory directory, List<String> fileNames) {
	}

}
