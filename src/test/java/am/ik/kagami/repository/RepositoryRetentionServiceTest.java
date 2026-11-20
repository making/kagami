package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.storage.LocalStorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RepositoryRetentionService}.
 */
class RepositoryRetentionServiceTest {

	private static final String REPOSITORY_ID = "repo";

	private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

	@TempDir
	Path tempDir;

	@Test
	void unsetPolicyKeepsEveryArtifact() throws IOException {
		RepositoryRetentionService service = service(KagamiProperties.Retention.builder().build());

		write("org/example/lib/1.0/lib-1.0.jar", "jar", NOW.minus(Duration.ofDays(365)));
		write("org/example/lib/1.0/lib-1.0.jar.sha1", "checksum", NOW.minus(Duration.ofDays(365)));

		RepositoryRetentionService.RetentionResult result = service.apply(REPOSITORY_ID);

		assertThat(service.isEnabled(REPOSITORY_ID)).isFalse();
		assertThat(result.evictedPaths()).isEmpty();
		assertThat(result.failures()).isEmpty();
		assertThat(exists("org/example/lib/1.0/lib-1.0.jar")).isTrue();
		assertThat(exists("org/example/lib/1.0/lib-1.0.jar.sha1")).isTrue();
	}

	@Test
	void maxAgeEvictsTheArtifactTogetherWithItsSidecars() throws IOException {
		RepositoryRetentionService service = service(
				KagamiProperties.Retention.builder().maxAge(Duration.ofDays(30)).build());

		Instant old = NOW.minus(Duration.ofDays(90));
		write("org/example/lib/1.0/lib-1.0.jar", "jar", old);
		write("org/example/lib/1.0/lib-1.0.jar.sha1", "sha1", old);
		write("org/example/lib/1.0/lib-1.0.jar.sha256", "sha256", old);
		write("org/example/lib/1.0/lib-1.0.jar.attestation.sigstore.json", "bundle", old);
		write("org/example/lib/2.0/lib-2.0.jar", "jar2", NOW.minus(Duration.ofDays(1)));
		write("org/example/lib/2.0/lib-2.0.jar.sha1", "sha1-2", NOW.minus(Duration.ofDays(1)));

		RepositoryRetentionService.RetentionResult result = service.apply(REPOSITORY_ID);

		assertThat(result.evictedPaths()).containsExactly("org/example/lib/1.0/lib-1.0.jar");
		assertThat(result.failures()).isEmpty();
		assertThat(exists("org/example/lib/1.0/lib-1.0.jar")).isFalse();
		assertThat(exists("org/example/lib/1.0/lib-1.0.jar.sha1")).isFalse();
		assertThat(exists("org/example/lib/1.0/lib-1.0.jar.sha256")).isFalse();
		assertThat(exists("org/example/lib/1.0/lib-1.0.jar.attestation.sigstore.json")).isFalse();
		assertThat(exists("org/example/lib/1.0")).isFalse();
		assertThat(exists("org/example/lib/2.0/lib-2.0.jar")).isTrue();
		assertThat(exists("org/example/lib/2.0/lib-2.0.jar.sha1")).isTrue();

		SimpleMeterRegistry registry = this.registry;
		assertThat(registry.get("kagami.retention.evicted").tag("repository", REPOSITORY_ID).counter().count())
			.isEqualTo(1);
		assertThat(registry.get("kagami.retention.size").tag("repository", REPOSITORY_ID).gauge().value())
			.isEqualTo(10);
	}

	@Test
	void maxSizeEvictsTheOldestArtifactsFirst() throws IOException {
		RepositoryRetentionService service = service(
				KagamiProperties.Retention.builder().maxSize(DataSize.ofBytes(10)).build());

		Instant oldest = NOW.minus(Duration.ofDays(3));
		Instant middle = NOW.minus(Duration.ofDays(2));
		Instant newest = NOW.minus(Duration.ofDays(1));
		write("a.tgz", "aaaa", oldest);
		write("a.tgz.sha1", "aa", oldest);
		write("b.tgz", "bbbb", middle);
		write("c.tgz", "cccc", newest);

		RepositoryRetentionService.RetentionResult result = service.apply(REPOSITORY_ID);

		assertThat(result.evictedPaths()).containsExactly("a.tgz");
		assertThat(result.remainingSize()).isLessThanOrEqualTo(10);
		assertThat(exists("a.tgz")).isFalse();
		assertThat(exists("a.tgz.sha1")).isFalse();
		assertThat(exists("b.tgz")).isTrue();
		assertThat(exists("c.tgz")).isTrue();
	}

	@Test
	void aSingleOversizedArtifactIsStillEvicted() throws IOException {
		RepositoryRetentionService service = service(
				KagamiProperties.Retention.builder().maxSize(DataSize.ofBytes(1)).build());
		write("big.tgz", "more than one byte", NOW);

		RepositoryRetentionService.RetentionResult result = service.apply(REPOSITORY_ID);

		assertThat(result.evictedPaths()).containsExactly("big.tgz");
		assertThat(result.remainingSize()).isZero();
		assertThat(exists("big.tgz")).isFalse();
	}

	@Test
	void bookkeepingFilesAreNotEvictedOnTheirOwn() throws IOException {
		RepositoryRetentionService service = service(
				KagamiProperties.Retention.builder().maxAge(Duration.ofDays(1)).build());
		write("org/example/lib/maven-metadata.xml", "metadata", NOW.minus(Duration.ofDays(9)));
		write("org/example/lib/maven-metadata.xml.sha1", "checksum", NOW.minus(Duration.ofDays(9)));
		write("org/example/lib/resolver-status.properties", "status", NOW.minus(Duration.ofDays(9)));

		RepositoryRetentionService.RetentionResult result = service.apply(REPOSITORY_ID);

		assertThat(result.evictedPaths()).isEmpty();
		assertThat(exists("org/example/lib/maven-metadata.xml")).isTrue();
		assertThat(exists("org/example/lib/maven-metadata.xml.sha1")).isTrue();
		assertThat(exists("org/example/lib/resolver-status.properties")).isTrue();
	}

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private RepositoryRetentionService service(KagamiProperties.Retention retention) {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path(this.tempDir.toString()).build())
			.repositories(Map.of(REPOSITORY_ID,
					KagamiProperties.Repository.builder().url("https://example.com").retention(retention).build()))
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new RepositoryRetentionService(properties, new LocalStorageService(properties), InstantSource.fixed(NOW),
				this.registry);
	}

	private Path write(String path, String content, Instant lastModified) throws IOException {
		Path file = this.tempDir.resolve(REPOSITORY_ID).resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		Files.setLastModifiedTime(file, FileTime.from(lastModified));
		return file;
	}

	private boolean exists(String path) {
		return Files.exists(this.tempDir.resolve(REPOSITORY_ID).resolve(path));
	}

}
