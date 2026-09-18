package am.ik.kagami.storage;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser API tests against the S3 storage backend: repository listing, breadcrumbs and
 * file info with checksums.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"spring.security.user.name=test-user", "spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", roles = "USER")
class S3BrowserControllerTest {

	static final GenericContainer<?> RUSTFS = Rustfs.container();

	static final String BUCKET = "kagami-browser-" + UUID.randomUUID();

	static S3Client s3Client;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StorageService storageService;

	@BeforeAll
	static void startRustfs() {
		RUSTFS.start();
		s3Client = Rustfs.client(RUSTFS);
		Rustfs.createBucket(s3Client, BUCKET);
	}

	@AfterAll
	static void stopRustfs() {
		if (s3Client != null) {
			s3Client.close();
		}
		RUSTFS.stop();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("kagami.storage.s3.key-prefix", () -> "test-" + UUID.randomUUID() + "/");
		registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
		registry.add("spring.cloud.aws.credentials.access-key", () -> Rustfs.ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> Rustfs.SECRET_KEY);
		registry.add("spring.cloud.aws.s3.endpoint", () -> Rustfs.endpoint(RUSTFS));
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
	}

	@Test
	void browseRepository_returnsDirectoriesAndFiles() throws Exception {
		store("org/example/lib/1.0/lib-1.0.jar", "jar content");
		store("org/example/lib/1.0/lib-1.0.jar.sha1", "checksum");

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org/example/lib"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentPath").value("org/example/lib"))
			.andExpect(jsonPath("$.entries[0].name").value("1.0"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"));
	}

	@Test
	void browseRepository_returnsParentPath() throws Exception {
		store("org/example/lib/1.0/lib-1.0.jar", "jar content");

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org/example"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parentPath").value("org"))
			.andExpect(jsonPath("$.entries[0].name").value("lib"));
	}

	@Test
	void getFileInfo_returnsSizeAndChecksums() throws Exception {
		store("org/example/lib-1.0.jar", "test content");
		store("org/example/lib-1.0.jar.sha1", "abc123");
		store("org/example/lib-1.0.jar.sha256", "def456");

		this.mockMvc.perform(get("/repositories/test-repo/info").param("path", "org/example/lib-1.0.jar"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("lib-1.0.jar"))
			.andExpect(jsonPath("$.type").value("file"))
			.andExpect(jsonPath("$.contentType").value("application/java-archive"))
			.andExpect(jsonPath("$.size").value(12))
			.andExpect(jsonPath("$.sha1").value("abc123"))
			.andExpect(jsonPath("$.sha256").value("def456"));
	}

	private void store(String path, String content) throws Exception {
		try (var inputStream = new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation("test-repo", path), inputStream);
		}
	}

}
