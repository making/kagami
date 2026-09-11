package am.ik.kagami.browser.web;

import am.ik.kagami.RustfsContainer;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser API tests against the S3 storage backend.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"spring.security.user.name=test-user", "spring.security.user.password=test-password" })
@TestPropertySource(properties = "kagami.storage.type=s3")
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
class S3StorageBrowserControllerTest {

	private static final String REPOSITORY_ID = "test-repo";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StorageService storageService;

	@DynamicPropertySource
	static void configureStorage(DynamicPropertyRegistry registry) {
		RustfsContainer.registerProperties(registry);
	}

	@BeforeEach
	void setUp() throws IOException {
		RustfsContainer.clearBucket();
		store("org/springframework/test-file.jar", "dummy jar content");
		store("test.jar", "test content");
		store("test.jar.sha1", "abc123");
		store("test.jar.sha256", "def456");
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
	void browseRepository_shouldReturnDirectChildrenWithBreadcrumbs() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoryId").value(REPOSITORY_ID))
			.andExpect(jsonPath("$.currentPath").value("org"))
			.andExpect(jsonPath("$.parentPath").value(""))
			.andExpect(jsonPath("$.entries[0].name").value("springframework"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"))
			// Object storage has no timestamp for a common prefix
			.andExpect(jsonPath("$.entries[0].lastModified").doesNotExist());

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org/springframework"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parentPath").value("org"))
			.andExpect(jsonPath("$.entries[0].name").value("test-file.jar"))
			.andExpect(jsonPath("$.entries[0].type").value("file"))
			.andExpect(jsonPath("$.entries[0].size").value(17));
	}

	@Test
	void getFileInfo_shouldReturnSizeAndChecksums() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "test.jar"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("test.jar"))
			.andExpect(jsonPath("$.type").value("file"))
			.andExpect(jsonPath("$.size").value(12))
			.andExpect(jsonPath("$.contentType").value("application/java-archive"))
			.andExpect(jsonPath("$.sha1").value("abc123"))
			.andExpect(jsonPath("$.sha256").value("def456"));
	}

	@Test
	void browseRepository_whenRepositoryNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/unknown-repo/browse")).andExpect(status().isBadRequest());
	}

	@Test
	void getFileInfo_whenFileNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "nonexistent.jar"))
			.andExpect(status().isBadRequest());
	}

	private void store(String path, String content) throws IOException {
		ArtifactLocation location = new ArtifactLocation(REPOSITORY_ID, path);
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(location, inputStream);
		}
	}

}
