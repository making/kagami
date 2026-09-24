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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the cache administration page and its htmx fragments.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"spring.security.user.name=test-user", "spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", authorities = "artifacts:admin")
class RepositoryAdminControllerTest {

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
	void adminPageShowsNavigationAndGarbageCollectionForm() throws Exception {
		this.mockMvc.perform(get("/admin"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("href=\"/admin\"")))
			.andExpect(content().string(containsString("Run Garbage Collection")))
			.andExpect(content().string(containsString("hx-indicator=\"#gc-run-button\"")))
			.andExpect(content().string(containsString("id=\"gc-run-button\"")))
			.andExpect(content().string(containsString("built-in default PEM key pair")))
			.andExpect(content().string(containsString("maven-metadata.xml")))
			.andExpect(content().string(containsString("resolver-status.properties")));
	}

	@Test
	@WithMockUser(username = "test-user", authorities = "artifacts:read")
	void nonAdminDoesNotSeeAdminNavigation() throws Exception {
		this.mockMvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("href=\"/admin\""))));
	}

	@Test
	@WithMockUser(username = "test-user", authorities = "artifacts:read")
	void nonAdminCannotOpenAdminPage() throws Exception {
		this.mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
	}

	@Test
	void previewReturnsEligibleDirectories() throws Exception {
		seedMetadataOnly("preview");

		this.mockMvc
			.perform(get("/app/admin/gc/preview").param("repositoryId", "test-repo")
				.param("olderThanHours", "1")
				.header("HX-Request", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("preview/missing")));
	}

	@Test
	void emptyPreviewUsesInformationalMessage() throws Exception {
		this.mockMvc
			.perform(get("/app/admin/gc/preview").param("repositoryId", "test-repo")
				.param("olderThanHours", "1")
				.header("HX-Request", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("alert alert-info")))
			.andExpect(content().string(containsString("No eligible metadata-only directories were found.")));
	}

	@Test
	void collectionRemovesEligibleDirectoriesFromTheAdminScreen() throws Exception {
		Path candidate = seedMetadataOnly("collection");

		this.mockMvc
			.perform(post("/app/admin/gc").param("repositoryId", "test-repo")
				.param("olderThanHours", "1")
				.header("HX-Request", "true")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Collection complete")))
			.andExpect(content().string(containsString("collection/missing")));

		assertThat(candidate).doesNotExist();
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
