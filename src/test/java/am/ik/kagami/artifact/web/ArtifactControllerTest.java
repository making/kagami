package am.ik.kagami.artifact.web;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ArtifactController
 */
@SpringBootTest(properties = { "kagami.repositories.test-central.url=https://repo.maven.apache.org/maven2",
		"logging.level.am.ik.kagami=DEBUG", "spring.security.user.name=test-user",
		"spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
class ArtifactControllerTest {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void getArtifact_whenNotInCache_shouldFetchFromRemoteAndCache() throws Exception {
		// First request - artifact not in cache
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.pom"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"))
			.andExpect(header().string("Cache-Control", "max-age=31536000, public"));

		// Verify artifact was cached
		Path cachedFile = tempDir.resolve("test-central/junit/junit/4.13.2/junit-4.13.2.pom");
		assertThat(cachedFile).exists();
		assertThat(Files.size(cachedFile)).isGreaterThan(0);

		// Second request - should serve from cache without hitting remote
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.pom"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"));
	}

	@Test
	void getArtifact_whenRepositoryNotConfigured_shouldReturn404() throws Exception {
		this.mockMvc.perform(get("/artifacts/unknown-repo/some/artifact/1.0/artifact-1.0.jar"))
			.andExpect(status().isNotFound());
	}

	@Test
	void deleteArtifact_whenFileExists_shouldDeleteAndReturn204() throws Exception {
		// Create a test file
		Path testFile = tempDir.resolve("test-central/test/artifact/1.0/artifact-1.0.jar");
		Files.createDirectories(testFile.getParent());
		Files.writeString(testFile, "test content");

		// Delete the file
		this.mockMvc.perform(delete("/artifacts/test-central/test/artifact/1.0/artifact-1.0.jar"))
			.andExpect(status().isNoContent());

		// Verify file was deleted
		assertThat(testFile).doesNotExist();
	}

	@Test
	void deleteArtifact_whenDirectoryExists_shouldDeleteRecursivelyAndReturn204() throws Exception {
		// Create a test directory structure
		Path testDir = tempDir.resolve("test-central/test/artifact/1.0");
		Files.createDirectories(testDir);
		Files.writeString(testDir.resolve("artifact-1.0.jar"), "jar content");
		Files.writeString(testDir.resolve("artifact-1.0.pom"), "pom content");

		// Delete the directory
		this.mockMvc.perform(delete("/artifacts/test-central/test/artifact/1.0/")).andExpect(status().isNoContent());

		// Verify directory was deleted
		assertThat(testDir).doesNotExist();
	}

	@Test
	void deleteArtifact_whenNotExists_shouldReturn404() throws Exception {
		this.mockMvc.perform(delete("/artifacts/test-central/non/existent/artifact.jar"))
			.andExpect(status().isNotFound());
	}

	@Test
	void getArtifact_withDifferentContentTypes() throws Exception {
		// JAR file
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.jar"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/java-archive"));

		// SHA1 checksum
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.jar.sha1"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("text/plain"));

		// Maven metadata
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/maven-metadata.xml"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"));
	}

}