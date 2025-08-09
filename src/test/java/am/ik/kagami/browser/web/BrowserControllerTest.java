package am.ik.kagami.browser.web;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for BrowserController
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"logging.level.am.ik.kagami=DEBUG", "spring.security.user.name=test-user",
		"spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
class BrowserControllerTest {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void getRepositories_shouldReturnConfiguredRepositories() throws Exception {
		this.mockMvc.perform(get("/repositories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositories").isArray())
			.andExpect(jsonPath("$.repositories[?(@.id == 'test-repo')]").exists())
			.andExpect(jsonPath("$.repositories[?(@.id == 'test-repo')].url")
				.value("https://repo.maven.apache.org/maven2"));
	}

	@Test
	void browseRepository_whenRepositoryExists_shouldReturnBrowseResult() throws Exception {
		// Create some test directory structure
		Path repoDir = tempDir.resolve("test-repo");
		Path orgDir = repoDir.resolve("org");
		Path springDir = orgDir.resolve("springframework");
		Files.createDirectories(springDir);
		Files.writeString(springDir.resolve("test-file.jar"), "dummy jar content");

		var result = this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoryId").value("test-repo"))
			.andExpect(jsonPath("$.currentPath").value("org"))
			.andExpect(jsonPath("$.entries").isArray())
			.andExpect(jsonPath("$.entries[0].name").value("springframework"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"));

		// Debug: Print the actual response
		System.out.println("Directory Response: " + result.andReturn().getResponse().getContentAsString());

		// Also browse into springframework to see file entries
		var fileResult = this.mockMvc
			.perform(get("/repositories/test-repo/browse").param("path", "org/springframework"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[0].name").value("test-file.jar"))
			.andExpect(jsonPath("$.entries[0].type").value("file"))
			.andExpect(jsonPath("$.entries[0].size").value(17));

		System.out.println("File Response: " + fileResult.andReturn().getResponse().getContentAsString());
	}

	@Test
	void browseRepository_whenRepositoryNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/unknown-repo/browse")).andExpect(status().isBadRequest());
	}

	@Test
	void getFileInfo_whenFileExists_shouldReturnFileInfo() throws Exception {
		// Create test file with checksum
		Path repoDir = tempDir.resolve("test-repo");
		Path testFile = repoDir.resolve("test.jar");
		Files.createDirectories(repoDir);
		Files.writeString(testFile, "test content");
		Files.writeString(repoDir.resolve("test.jar.sha1"), "abc123");

		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "test.jar"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("test.jar"))
			.andExpect(jsonPath("$.type").value("file"))
			.andExpect(jsonPath("$.contentType").value("application/java-archive"))
			.andExpect(jsonPath("$.sha1").value("abc123"));
	}

	@Test
	void getFileInfo_whenFileNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "nonexistent.jar"))
			.andExpect(status().isBadRequest());
	}

}