package am.ik.kagami.browser.web;

import am.ik.kagami.RustfsContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser API (listing, breadcrumbs, file info with sha1/sha256) against the S3 storage
 * backend
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"logging.level.am.ik.kagami=DEBUG", "spring.security.user.name=test-user",
		"spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
class S3BrowserControllerTest {

	static final String BUCKET = "kagami-browser-api";

	static final RustfsContainer container = new RustfsContainer();

	@Autowired
	private MockMvc mockMvc;

	@AfterAll
	static void stopContainer() {
		container.close();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		if (!container.isRunning()) {
			container.start();
			container.createBucket(BUCKET);
		}
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("spring.cloud.aws.s3.enabled", () -> "true");
		registry.add("spring.cloud.aws.s3.endpoint", () -> container.endpoint());
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
		registry.add("spring.cloud.aws.region.static", () -> RustfsContainer.REGION);
		registry.add("spring.cloud.aws.credentials.access-key", () -> RustfsContainer.ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> RustfsContainer.SECRET_KEY);
	}

	@BeforeEach
	void seed() throws Exception {
		try (var client = container.client()) {
			String jarKey = "test-repo/org/springframework/test-file.jar";
			client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(jarKey).build(),
					RequestBody.fromBytes("dummy jar content".getBytes()));
			String sha1Key = "test-repo/test.jar.sha1";
			client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(sha1Key).build(),
					RequestBody.fromBytes("abc123".getBytes()));
			String testJarKey = "test-repo/test.jar";
			client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(testJarKey).build(),
					RequestBody.fromBytes("test content".getBytes()));
		}
	}

	@AfterEach
	void emptyBucket() {
		container.deleteAllObjects(BUCKET);
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
	void browseRepository_shouldReturnDirectories() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoryId").value("test-repo"))
			.andExpect(jsonPath("$.currentPath").value("org"))
			.andExpect(jsonPath("$.parentPath").value(""))
			.andExpect(jsonPath("$.entries").isArray())
			.andExpect(jsonPath("$.entries[0].name").value("springframework"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"));
	}

	@Test
	void browseRepository_shouldReturnFiles() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org/springframework"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[0].name").value("test-file.jar"))
			.andExpect(jsonPath("$.entries[0].type").value("file"))
			.andExpect(jsonPath("$.entries[0].size").value(17));
	}

	@Test
	void browseRepository_whenRepositoryNotExists_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/repositories/unknown-repo/browse")).andExpect(status().isBadRequest());
	}

	@Test
	void getFileInfo_shouldReturnFileInfoWithChecksums() throws Exception {
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
