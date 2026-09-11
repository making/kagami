package am.ik.kagami.browser.web;

import am.ik.kagami.S3TestSupport;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the browser API against the S3 storage backend: directory listing with breadcrumbs
 * and file info with checksums.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://example.com/maven2",
		"spring.security.user.name=test-user", "spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
class S3BrowserControllerTest {

	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		S3TestSupport.registerS3Properties(registry, "browser-" + System.nanoTime());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StorageService storageService;

	private void store(String artifactPath, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation("test-repo", artifactPath), inputStream);
		}
	}

	@Test
	void browseRepository_listsDirectoriesAndFiles() throws Exception {
		store("org/example/app/1.0/app-1.0.jar", "binary-content");
		store("org/example/app/1.0/app-1.0.jar.sha1", "abc123");
		store("org/example/other/note.txt", "note");

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", ""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[0].name").value("org"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"));

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org/example"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentPath").value("org/example"))
			.andExpect(jsonPath("$.parentPath").value("org"))
			.andExpect(jsonPath("$.entries[?(@.name == 'app')].type").value("directory"))
			.andExpect(jsonPath("$.entries[?(@.name == 'other')].type").value("directory"));

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org/example/app/1.0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parentPath").value("org/example/app"))
			.andExpect(jsonPath("$.entries[?(@.name == 'app-1.0.jar')].type").value("file"))
			.andExpect(jsonPath("$.entries[?(@.name == 'app-1.0.jar')].size").value(14))
			.andExpect(jsonPath("$.entries[?(@.name == 'app-1.0.jar.sha1')].type").value("file"));
	}

	@Test
	void getFileInfo_returnsSizeAndChecksums() throws Exception {
		store("org/example/app/1.0/app-1.0.jar", "binary-content");
		store("org/example/app/1.0/app-1.0.jar.sha1", "abc123");
		store("org/example/app/1.0/app-1.0.jar.sha256", "def456");

		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "org/example/app/1.0/app-1.0.jar"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("app-1.0.jar"))
			.andExpect(jsonPath("$.type").value("file"))
			.andExpect(jsonPath("$.contentType").value("application/java-archive"))
			.andExpect(jsonPath("$.size").value(14))
			.andExpect(jsonPath("$.sha1").value("abc123"))
			.andExpect(jsonPath("$.sha256").value("def456"));
	}

}
