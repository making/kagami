package am.ik.kagami.storage;

import am.ik.kagami.repository.RepositoryGarbageCollector;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionResult;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageDirectory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Contract every {@link StorageService} implementation has to satisfy. A backend test
 * subclasses this and provides a fresh, empty service per test.
 */
public abstract class StorageServiceContractTest {

	protected static final String REPOSITORY_ID = "test-repo";

	/**
	 * @return the service under test, backed by empty storage
	 */
	protected abstract StorageService storageService();

	@Test
	void storeAndRetrieve() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.jar");
		store(storage, location, "jar content");

		Optional<Resource> retrieved = storage.retrieve(location);
		assertThat(retrieved).isPresent();
		assertThat(read(retrieved.get())).isEqualTo("jar content");
		assertThat(retrieved.get().getFilename()).isEqualTo("lib-1.0.jar");
	}

	@Test
	void storeReplacesExistingContent() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.pom");
		store(storage, location, "first");
		store(storage, location, "second");

		assertThat(read(storage.retrieve(location).orElseThrow())).isEqualTo("second");
	}

	@Test
	void retrieveMissingReturnsEmpty() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.retrieve(location("org/missing.jar"))).isEmpty();
		store(storage, location("org/example/lib.jar"), "x");
		// A directory is not retrievable as an object
		assertThat(storage.retrieve(location("org/example"))).isEmpty();
	}

	@Test
	void storeAndRetrieveRejectRepositoryRoot() {
		StorageService storage = storageService();
		ArtifactLocation root = ArtifactLocation.root(REPOSITORY_ID);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> storage.store(root, new ByteArrayInputStream(new byte[0])));
		assertThatIllegalArgumentException().isThrownBy(() -> storage.retrieve(root));
		assertThatIllegalArgumentException().isThrownBy(() -> storage.deleteIfEmpty(root));
		assertThatIllegalArgumentException().isThrownBy(() -> storage.deleteFile(root));
	}

	@Test
	void statFile() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.jar");
		store(storage, location, "jar content");

		Optional<StorageEntry> stat = storage.stat(location);
		assertThat(stat).isPresent();
		StorageEntry entry = stat.get();
		assertThat(entry.name()).isEqualTo("lib-1.0.jar");
		assertThat(entry.type()).isEqualTo(StorageEntryType.FILE);
		assertThat(entry.path()).isEqualTo("org/example/lib/1.0/lib-1.0.jar");
		assertThat(entry.size()).isEqualTo(11L);
		assertThat(entry.lastModified()).isNotNull();
	}

	@Test
	void statDirectory() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content");

		Optional<StorageEntry> stat = storage.stat(location("org/example"));
		assertThat(stat).isPresent();
		StorageEntry entry = stat.get();
		assertThat(entry.name()).isEqualTo("example");
		assertThat(entry.type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(entry.path()).isEqualTo("org/example");
		assertThat(entry.size()).isNull();

		Optional<StorageEntry> root = storage.stat(ArtifactLocation.root(REPOSITORY_ID));
		assertThat(root).isPresent();
		assertThat(root.get().type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(root.get().path()).isEmpty();
	}

	@Test
	void statMissingReturnsEmpty() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.stat(location("org/missing.jar"))).isEmpty();
		assertThat(storage.stat(ArtifactLocation.root(REPOSITORY_ID))).isEmpty();
	}

	@Test
	void listReturnsDirectChildrenSortedByName() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content");
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom");
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha1"), "sha1");
		store(storage, location("org/example/lib/maven-metadata.xml"), "metadata");
		store(storage, location("org/another/README"), "readme");

		List<StorageEntry> root = storage.list(ArtifactLocation.root(REPOSITORY_ID));
		assertThat(root).extracting(StorageEntry::name).containsExactly("org");
		assertThat(root.getFirst().type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(root.getFirst().path()).isEqualTo("org");

		List<StorageEntry> org = storage.list(location("org"));
		assertThat(org).extracting(StorageEntry::name).containsExactly("another", "example");
		assertThat(org).extracting(StorageEntry::path).containsExactly("org/another", "org/example");
		assertThat(org).allMatch(StorageEntry::isDirectory);
		assertThat(org).extracting(StorageEntry::size).containsOnlyNulls();

		List<StorageEntry> lib = storage.list(location("org/example/lib"));
		assertThat(lib).extracting(StorageEntry::name).containsExactly("1.0", "maven-metadata.xml");
		assertThat(lib.get(0).type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(lib.get(1).type()).isEqualTo(StorageEntryType.FILE);
		assertThat(lib.get(1).size()).isEqualTo(8L);
		assertThat(lib.get(1).lastModified()).isNotNull();

		List<StorageEntry> version = storage.list(location("org/example/lib/1.0/"));
		assertThat(version).extracting(StorageEntry::name)
			.containsExactly("lib-1.0.jar", "lib-1.0.jar.sha1", "lib-1.0.pom");
		assertThat(version).allMatch(StorageEntry::isFile);
		assertThat(version).extracting(StorageEntry::path)
			.containsExactly("org/example/lib/1.0/lib-1.0.jar", "org/example/lib/1.0/lib-1.0.jar.sha1",
					"org/example/lib/1.0/lib-1.0.pom");
	}

	@Test
	void listMissingOrFileReturnsEmpty() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.list(ArtifactLocation.root(REPOSITORY_ID))).isEmpty();
		assertThat(storage.list(location("org/missing"))).isEmpty();
		store(storage, location("org/example/lib.jar"), "x");
		assertThat(storage.list(location("org/example/lib.jar"))).isEmpty();
	}

	@Test
	void deleteSingleObject() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation jar = location("org/example/lib/1.0/lib-1.0.jar");
		ArtifactLocation pom = location("org/example/lib/1.0/lib-1.0.pom");
		store(storage, jar, "jar");
		store(storage, pom, "pom");

		assertThat(storage.delete(jar)).isTrue();
		assertThat(storage.retrieve(jar)).isEmpty();
		assertThat(storage.stat(jar)).isEmpty();
		// Siblings are untouched
		assertThat(storage.retrieve(pom)).isPresent();
		assertThat(storage.list(location("org/example/lib/1.0"))).extracting(StorageEntry::name)
			.containsExactly("lib-1.0.pom");
	}

	@Test
	void deleteDirectoryRemovesEverythingUnderneath() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar");
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom");
		store(storage, location("org/example/lib/2.0/lib-2.0.jar"), "jar");
		store(storage, location("org/other/x.jar"), "x");

		assertThat(storage.delete(location("org/example/"))).isTrue();
		assertThat(storage.stat(location("org/example"))).isEmpty();
		assertThat(storage.retrieve(location("org/example/lib/1.0/lib-1.0.jar"))).isEmpty();
		assertThat(storage.retrieve(location("org/example/lib/2.0/lib-2.0.jar"))).isEmpty();
		assertThat(storage.list(location("org"))).extracting(StorageEntry::name).containsExactly("other");
	}

	@Test
	void deleteMissingReturnsFalse() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.delete(location("org/missing.jar"))).isFalse();
		assertThat(storage.delete(location("org/missing"))).isFalse();
	}

	@Test
	void deleteFileDeletesOnlyAFile() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation file = location("org/example/file.txt");
		store(storage, file, "content");

		assertThat(storage.deleteFile(file)).isTrue();
		assertThat(storage.retrieve(file)).isEmpty();
		assertThat(storage.deleteFile(file)).isFalse();
	}

	@Test
	void deleteFileDoesNotDeleteDirectoryDescendants() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation child = location("org/example/child.txt");
		store(storage, child, "content");

		assertThat(storage.deleteFile(location("org/example"))).isFalse();
		assertThat(storage.retrieve(child)).isPresent();
	}

	@Test
	void deleteIfEmptyDoesNotDeleteANonEmptyDirectory() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation file = location("org/example/keep.txt");
		store(storage, file, "content");

		assertThat(storage.deleteIfEmpty(location("org/example"))).isFalse();
		assertThat(storage.retrieve(file)).isPresent();
	}

	@Test
	void garbageCollectorRemovesOnlyOldMetadataOnlyDirectories() throws IOException {
		StorageService storage = storageService();
		String candidatePath = "org/old/missing";
		store(storage, location(candidatePath + "/maven-metadata.xml"), "metadata");
		store(storage, location(candidatePath + "/maven-metadata.xml.sha1"), "checksum");
		store(storage, location("org/partial/maven-metadata.xml"), "metadata");
		store(storage, location("org/extra/maven-metadata.xml"), "metadata");
		store(storage, location("org/extra/maven-metadata.xml.sha1"), "checksum");
		store(storage, location("org/extra/keep.txt"), "keep");
		store(storage, location("org/nested/maven-metadata.xml"), "metadata");
		store(storage, location("org/nested/maven-metadata.xml.sha1"), "checksum");
		store(storage, location("org/nested/child/keep.txt"), "keep");
		store(storage, location("org/valid/maven-metadata.xml"), "metadata");
		store(storage, location("org/valid/maven-metadata.xml.sha1"), "checksum");
		store(storage, location("org/valid/artifact.jar"), "artifact");
		InstantSource future = InstantSource.fixed(Instant.now().plus(Duration.ofHours(2)));
		RepositoryGarbageCollector collector = new RepositoryGarbageCollector(storage, future);

		List<GarbageDirectory> candidates = collector.findCandidates(REPOSITORY_ID, Duration.ofHours(1));
		assertThat(candidates).extracting(GarbageDirectory::path).containsExactly(candidatePath);

		GarbageCollectionResult result = collector.collect(REPOSITORY_ID, Duration.ofHours(1));
		assertThat(result.collectedPaths()).containsExactly(candidatePath);
		assertThat(result.failures()).isEmpty();
		assertThat(storage.stat(location(candidatePath))).isEmpty();
		assertThat(storage.stat(location("org/old"))).isEmpty();
		assertThat(storage.retrieve(location("org/partial/maven-metadata.xml"))).isPresent();
		assertThat(storage.retrieve(location("org/extra/keep.txt"))).isPresent();
		assertThat(storage.retrieve(location("org/nested/child/keep.txt"))).isPresent();
		assertThat(storage.retrieve(location("org/valid/artifact.jar"))).isPresent();
		assertThat(collector.findCandidates(REPOSITORY_ID, Duration.ofHours(1))).isEmpty();
	}

	@Test
	void garbageCollectorHonorsTheMinimumAge() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/recent/maven-metadata.xml"), "metadata");
		store(storage, location("org/recent/maven-metadata.xml.sha1"), "checksum");
		Instant storedAt = Instant.now();
		RepositoryGarbageCollector tooSoon = new RepositoryGarbageCollector(storage,
				InstantSource.fixed(storedAt.plus(Duration.ofMinutes(30))));
		RepositoryGarbageCollector oldEnough = new RepositoryGarbageCollector(storage,
				InstantSource.fixed(storedAt.plus(Duration.ofHours(2))));

		assertThat(tooSoon.findCandidates(REPOSITORY_ID, Duration.ofHours(1))).isEmpty();
		assertThat(oldEnough.findCandidates(REPOSITORY_ID, Duration.ofHours(1))).extracting(GarbageDirectory::path)
			.containsExactly("org/recent");
	}

	@Test
	void statsCountArtifactsButNotAuxiliaryFiles() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.stats(REPOSITORY_ID)).isEqualTo(StorageStats.EMPTY);

		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content"); // 11
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha1"), "sha1"); // 4
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha256"), "sha256"); // 6
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.md5"), "md5"); // 3
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom"); // 3
		store(storage, location("org/example/lib/maven-metadata.xml"), "metadata"); // 8

		StorageStats stats = storage.stats(REPOSITORY_ID);
		assertThat(stats.artifactCount()).isEqualTo(2);
		assertThat(stats.totalSize()).isEqualTo(35);
		assertThat(stats.lastUpdated()).isNotNull();
		assertThat(stats.lastUpdated())
			.isEqualTo(storage.stat(location("org/example/lib/maven-metadata.xml")).orElseThrow().lastModified());
	}

	@Test
	void statsOfUnknownRepositoryIsEmpty() throws IOException {
		assertThat(storageService().stats("unknown")).isEqualTo(StorageStats.EMPTY);
	}

	protected static ArtifactLocation location(String artifactPath) {
		return new ArtifactLocation(REPOSITORY_ID, artifactPath);
	}

	protected static void store(StorageService storage, ArtifactLocation location, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			storage.store(location, inputStream);
		}
	}

	protected static String read(Resource resource) throws IOException {
		try (InputStream inputStream = resource.getInputStream()) {
			return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
		}
	}

}
