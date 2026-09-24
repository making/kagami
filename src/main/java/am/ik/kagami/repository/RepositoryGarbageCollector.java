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
 * Finds and removes metadata-only cache directories left behind by failed Maven cache
 * requests.
 * <p>
 * A candidate is a repository directory whose direct children are exactly
 * {@code maven-metadata.xml} and {@code maven-metadata.xml.sha1}. The files must be at
 * least the requested age old. The two files are removed individually after a second
 * eligibility check, so a request that adds an artifact concurrently cannot make the
 * collector remove that artifact.
 */
@Service
public class RepositoryGarbageCollector {

	private static final Logger logger = LoggerFactory.getLogger(RepositoryGarbageCollector.class);

	private static final List<String> METADATA_FILES = List.of("maven-metadata.xml", "maven-metadata.xml.sha1");

	private static final Set<String> METADATA_FILE_SET = Set.copyOf(METADATA_FILES);

	private final StorageService storageService;

	private final InstantSource instantSource;

	public RepositoryGarbageCollector(StorageService storageService, InstantSource instantSource) {
		this.storageService = storageService;
		this.instantSource = instantSource;
	}

	/**
	 * Find metadata-only directories without changing storage.
	 * @param repositoryId the repository to inspect
	 * @param olderThan the minimum age of both metadata files; must not be negative
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
	 * Remove metadata-only directories that pass the age check.
	 * @param repositoryId the repository to inspect
	 * @param olderThan the minimum age of both metadata files; must not be negative
	 * @return the directories whose metadata was collected and any per-directory failures
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
				// Re-check immediately before deleting. This protects against a metadata
				// refresh or an artifact download that completed after the initial walk.
				if (garbageDirectory(directory, cutoff).isEmpty()) {
					continue;
				}
				collectFiles(directory);
				// Local storage has real directory entries; object storage disappears
				// automatically after its last object is deleted. Directory cleanup is
				// best effort: the metadata files are already gone at this point.
				try {
					this.storageService.deleteIfEmpty(directory);
					pruneEmptyAncestors(directory);
				}
				catch (IOException e) {
					logger.warn("Failed to prune empty ancestors for {}/{}", repositoryId, candidate.path(), e);
				}
				collectedPaths.add(candidate.path());
				logger.info("Collected metadata-only directory {}/{}", repositoryId, candidate.path());
			}
			catch (IOException e) {
				String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				failures.add(new GarbageCollectionFailure(candidate.path(), message));
				logger.warn("Failed to collect metadata-only directory {}/{}", repositoryId, candidate.path(), e);
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
			Optional<GarbageDirectory> candidate = garbageDirectory(child, cutoff);
			if (candidate.isPresent()) {
				candidates.add(candidate.get());
			}
			else {
				findCandidates(child, cutoff, candidates);
			}
		}
	}

	private Optional<GarbageDirectory> garbageDirectory(ArtifactLocation directory, Instant cutoff) throws IOException {
		List<StorageEntry> children = this.storageService.list(directory);
		if (children.size() != METADATA_FILES.size()) {
			return Optional.empty();
		}
		Set<String> names = new HashSet<>();
		List<Instant> modifiedTimes = new ArrayList<>(children.size());
		for (StorageEntry child : children) {
			Instant modified = child.lastModified();
			if (!child.isFile() || !METADATA_FILE_SET.contains(child.name()) || modified == null
					|| modified.isAfter(cutoff)) {
				return Optional.empty();
			}
			names.add(child.name());
			modifiedTimes.add(modified);
		}
		if (!names.equals(METADATA_FILE_SET)) {
			return Optional.empty();
		}
		return Optional.of(new GarbageDirectory(directory.artifactPath(),
				modifiedTimes.stream().max(Comparator.naturalOrder()).orElseThrow()));
	}

	private void collectFiles(ArtifactLocation directory) throws IOException {
		IOException failure = null;
		for (String fileName : METADATA_FILES) {
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
	 * A directory that contains only the two Maven metadata files.
	 *
	 * @param path the path relative to the repository root
	 * @param lastModified the newer of the two file modification timestamps
	 */
	public record GarbageDirectory(String path, Instant lastModified) {
	}

	/**
	 * The result of collecting metadata-only directories.
	 *
	 * @param collectedPaths repository-relative paths whose metadata files were removed
	 * @param failures paths that could not be collected and their error messages
	 */
	public record GarbageCollectionResult(List<String> collectedPaths, List<GarbageCollectionFailure> failures) {
	}

	/**
	 * A failure to collect one metadata-only directory.
	 *
	 * @param path the path relative to the repository root
	 * @param message the storage error message
	 */
	public record GarbageCollectionFailure(String path, String message) {
	}

}
