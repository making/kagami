package am.ik.kagami.repository.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the administrator cache collection API.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"spring.security.user.name=test-user", "spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", authorities = "artifacts:admin")
class RepositoryGarbageCollectionControllerTest {

	@TempDir
	static Path tempDir;

	@Autowired
	MockMvc mockMvc;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@BeforeEach
	void clearRepository() throws IOException {
		Path repository = tempDir.resolve("test-repo");
		if (!Files.exists(repository)) {
			return;
		}
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(repository)) {
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) {
			Files.deleteIfExists(path);
		}
	}

	@Test
	void previewReturnsCandidatesWithoutDeletingThem() throws Exception {
		Path candidate = seedMetadataOnly("preview");

		this.mockMvc.perform(get("/artifacts/test-repo/gc"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].path").value("preview/missing"))
			.andExpect(jsonPath("$[0].lastModified").exists());

		assertThat(candidate).exists();
	}

	@Test
	void previewIncludesResolverStatusOnlyDirectories() throws Exception {
		Path candidate = seedResolverStatusOnly("resolver-preview");

		this.mockMvc.perform(get("/artifacts/test-repo/gc"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].path").value("resolver-preview/missing"))
			.andExpect(jsonPath("$[0].lastModified").exists());

		assertThat(candidate).exists();
	}

	@Test
	void collectionRemovesOnlyMetadataOnlyDirectories() throws Exception {
		Path candidate = seedMetadataOnly("collection");
		Path valid = tempDir.resolve("test-repo/collection-valid");
		Files.createDirectories(valid);
		Files.writeString(valid.resolve("artifact.jar"), "artifact");
		Files.writeString(valid.resolve("maven-metadata.xml"), "metadata");
		Files.writeString(valid.resolve("maven-metadata.xml.sha1"), "checksum");

		this.mockMvc.perform(post("/artifacts/test-repo/gc").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.collectedPaths[0]").value("collection/missing"))
			.andExpect(jsonPath("$.failures").isEmpty());

		assertThat(candidate).doesNotExist();
		assertThat(valid.resolve("artifact.jar")).exists();
	}

	@Test
	void collectionRequiresCsrfForSessionAuthentication() throws Exception {
		Path candidate = seedMetadataOnly("csrf");

		this.mockMvc.perform(post("/artifacts/test-repo/gc")).andExpect(status().isForbidden());
		assertThat(candidate).exists();
	}

	@Test
	void recentMetadataIsNotCollectedByDefault() throws Exception {
		Path candidate = tempDir.resolve("test-repo/recent/missing");
		Files.createDirectories(candidate);
		Files.writeString(candidate.resolve("maven-metadata.xml"), "metadata");
		Files.writeString(candidate.resolve("maven-metadata.xml.sha1"), "checksum");

		this.mockMvc.perform(get("/artifacts/test-repo/gc"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
		assertThat(candidate).exists();
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read", "artifacts:delete" })
	void adminAuthorityIsRequiredForPreviewAndCollection() throws Exception {
		this.mockMvc.perform(get("/artifacts/test-repo/gc")).andExpect(status().isForbidden());
		this.mockMvc.perform(post("/artifacts/test-repo/gc")).andExpect(status().isForbidden());
	}

	@Test
	void unknownRepositoryReturnsNotFound() throws Exception {
		this.mockMvc.perform(get("/artifacts/unknown/gc")).andExpect(status().isNotFound());
	}

	@Test
	void negativeAgeIsRejected() throws Exception {
		this.mockMvc.perform(get("/artifacts/test-repo/gc").param("olderThan", "PT-1S"))
			.andExpect(status().isBadRequest());
	}

	private Path seedResolverStatusOnly(String name) throws IOException {
		Path candidate = tempDir.resolve("test-repo").resolve(name).resolve("missing");
		Files.createDirectories(candidate);
		FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
		Files.writeString(candidate.resolve("resolver-status.properties"), "resolver status");
		Files.setLastModifiedTime(candidate.resolve("resolver-status.properties"), old);
		return candidate;
	}

	private Path seedMetadataOnly(String name) throws IOException {
		Path candidate = tempDir.resolve("test-repo").resolve(name).resolve("missing");
		Files.createDirectories(candidate);
		FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
		Files.writeString(candidate.resolve("maven-metadata.xml"), "metadata");
		Files.writeString(candidate.resolve("maven-metadata.xml.sha1"), "checksum");
		Files.setLastModifiedTime(candidate.resolve("maven-metadata.xml"), old);
		Files.setLastModifiedTime(candidate.resolve("maven-metadata.xml.sha1"), old);
		return candidate;
	}

}
