package am.ik.kagami.browser.web;

import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for BrowserController. The storage backend is supplied by the
 * subclass; the repository content is seeded through {@link StorageService} so that the
 * same expectations hold for every backend.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"logging.level.am.ik.kagami=DEBUG", "spring.security.user.name=test-user",
		"spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
public abstract class BrowserControllerTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	private StorageService storageService;

	void seed(String path, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation("test-repo", path), inputStream);
		}
	}

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
		seed("org/springframework/test-file.jar", "dummy jar content");

		ResultActions result = this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoryId").value("test-repo"))
			.andExpect(jsonPath("$.currentPath").value("org"))
			.andExpect(jsonPath("$.parentPath").value(""))
			.andExpect(jsonPath("$.entries").isArray())
			.andExpect(jsonPath("$.entries[0].name").value("springframework"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"))
			.andExpect(jsonPath("$.entries[0].path").value("org/springframework"))
			.andExpect(jsonPath("$.entries[0].size").doesNotExist());

		// Debug: Print the actual response
		System.out.println("Directory Response: " + result.andReturn().getResponse().getContentAsString());

		// Also browse into springframework to see file entries
		ResultActions fileResult = this.mockMvc
			.perform(get("/repositories/test-repo/browse").param("path", "org/springframework"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentPath").value("org/springframework"))
			.andExpect(jsonPath("$.parentPath").value("org"))
			.andExpect(jsonPath("$.entries[0].name").value("test-file.jar"))
			.andExpect(jsonPath("$.entries[0].type").value("file"))
			.andExpect(jsonPath("$.entries[0].path").value("org/springframework/test-file.jar"))
			.andExpect(jsonPath("$.entries[0].size").value(17))
			.andExpect(jsonPath("$.entries[0].lastModified").exists());

		System.out.println("File Response: " + fileResult.andReturn().getResponse().getContentAsString());
	}

	@Test
	void browseRepository_whenRepositoryNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/unknown-repo/browse")).andExpect(status().isBadRequest());
	}

	@Test
	void getFileInfo_whenFileExists_shouldReturnFileInfo() throws Exception {
		seed("test.jar", "test content");
		seed("test.jar.sha1", "abc123");
		seed("test.jar.sha256", "def456\n");

		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "test.jar"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("test.jar"))
			.andExpect(jsonPath("$.path").value("test.jar"))
			.andExpect(jsonPath("$.type").value("file"))
			.andExpect(jsonPath("$.size").value(12))
			.andExpect(jsonPath("$.lastModified").exists())
			.andExpect(jsonPath("$.contentType").value("application/java-archive"))
			.andExpect(jsonPath("$.sha1").value("abc123"))
			.andExpect(jsonPath("$.sha256").value("def456"));
	}

	@Test
	void getFileInfo_whenFileNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "nonexistent.jar"))
			.andExpect(status().isBadRequest());
	}

}