package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.repository.RepositoryGarbageCollector;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionResult;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the storage contract against the local file system backend
 */
class LocalStorageServiceTest extends StorageServiceContractTest {

	@TempDir
	Path tempDir;

	@Test
	void garbageCollectorDoesNotFollowSymlinkDirectories() throws IOException {
		StorageService storage = storageService();
		Path external = this.tempDir.resolve("external");
		Path link = this.tempDir.resolve("test-repo/link");
		Files.createDirectories(external);
		Files.createDirectories(link.getParent());
		Files.writeString(external.resolve("maven-metadata.xml"), "metadata");
		Files.writeString(external.resolve("maven-metadata.xml.sha1"), "checksum");
		Files.createSymbolicLink(link, external);
		RepositoryGarbageCollector collector = new RepositoryGarbageCollector(storage,
				InstantSource.fixed(Instant.now().plus(Duration.ofHours(2))));

		assertThat(collector.findCandidates("test-repo", Duration.ofHours(1))).isEmpty();
		assertThat(external.resolve("maven-metadata.xml")).exists();
		assertThat(external.resolve("maven-metadata.xml.sha1")).exists();
	}

	@Test
	void garbageCollectorCollectsOnlyOldEmptyDirectories() throws IOException {
		Path repository = this.tempDir.resolve(REPOSITORY_ID);
		Path old = Files.createDirectories(repository.resolve("org/empty"));
		Path recent = Files.createDirectories(repository.resolve("org/recent"));
		Path nested = Files.createDirectories(repository.resolve("org/nested/empty-child"));
		Instant now = Instant.now();
		FileTime oldTime = FileTime.from(now.minus(Duration.ofHours(2)));
		Files.setLastModifiedTime(old, oldTime);
		Files.setLastModifiedTime(nested, oldTime);
		RepositoryGarbageCollector collector = new RepositoryGarbageCollector(storageService(),
				InstantSource.fixed(now));

		assertThat(collector.findCandidates(REPOSITORY_ID, Duration.ofHours(1))).extracting(GarbageDirectory::path)
			.containsExactly("org/empty", "org/nested/empty-child");

		GarbageCollectionResult result = collector.collect(REPOSITORY_ID, Duration.ofHours(1));
		assertThat(result.collectedPaths()).containsExactly("org/empty", "org/nested/empty-child");
		assertThat(result.failures()).isEmpty();
		assertThat(old).doesNotExist();
		assertThat(nested).doesNotExist();
		// The parent loses its last child and goes with it; the recent directory stays
		assertThat(repository.resolve("org/nested")).doesNotExist();
		assertThat(recent).exists();
	}

	@Override
	protected StorageService storageService() {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path(this.tempDir.toString()).build())
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new LocalStorageService(properties);
	}

}
