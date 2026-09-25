package am.ik.kagami.repository.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the server-rendered browse UI: the home page, the browse page and
 * the htmx fragments. The storage backend is supplied by the subclass; the repository
 * content is seeded through {@link StorageService} so that the same expectations hold for
 * every backend.
 */
@SpringBootTest(properties = { "kagami.repositories.test-repo.url=https://repo.maven.apache.org/maven2",
		"kagami.repositories.test-repo.sigstore.public-key-url=classpath:kagami-public.pem",
		"kagami.repositories.stats-repo.url=https://repo.maven.apache.org/maven2",
		"spring.security.user.name=test-user", "spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password",
		authorities = { "artifacts:read", "artifacts:delete" })
public abstract class RepositoryControllerTestBase {

	static final Path MOCK_COSIGN = mockCosignPath();

	@Autowired
	MockMvc mockMvc;

	@MockitoSpyBean
	private StorageService storageService;

	@DynamicPropertySource
	static void cosignProperties(DynamicPropertyRegistry registry) {
		// The mock cosign script keeps the fragment tests free of a real cosign binary
		registry.add("kagami.sigstore.cosign-path", () -> MOCK_COSIGN.toString());
	}

	private static Path mockCosignPath() {
		try {
			return Path.of(RepositoryControllerTestBase.class.getResource("/cosign-mock.sh").toURI());
		}
		catch (Exception e) {
			throw new IllegalStateException("cosign-mock.sh not found on the classpath", e);
		}
	}

	void seed(String path, String content) throws IOException {
		seed("test-repo", path, content);
	}

	void seed(String repositoryId, String path, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation(repositoryId, path), inputStream);
		}
	}

	String bodyOf(String url, String... params) throws Exception {
		MockHttpServletRequestBuilder request = get(url);
		for (int i = 0; i < params.length; i += 2) {
			request = request.param(params[i], params[i + 1]);
		}
		return this.mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void homePageShowsConfiguredRepositories() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");
		String body = bodyOf("/");
		assertThat(body).contains("test-repo");
		assertThat(body).contains("Repositories");
	}

	@Test
	void homePageDefersRepositoryStatistics() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");
		String body = bodyOf("/");
		assertThat(body).contains("hx-get=\"/fragments/repositories/stats\"");
		verify(this.storageService, never()).stats(anyString());
	}

	@Test
	void statsFragmentShowsRepositoryStatistics() throws Exception {
		// A repository of its own: the other tests leave their seeded files in test-repo
		seed("stats-repo", "org/springframework/test-file.jar", "dummy jar content");
		seed("stats-repo", "org/springframework/test-file.jar.sha1", "abc123");
		String body = bodyOf("/fragments/repositories/stats");
		String row = body.substring(body.indexOf("id=\"repository-stats-repo\""));
		row = row.substring(0, row.indexOf("</tr>"));
		// One artifact (the checksum is not counted), 17 + 6 bytes in total
		assertThat(row).contains("<span class=\"row-value\">1</span>");
		assertThat(row).contains("<span class=\"row-value\">23 B</span>");
		assertThat(body).contains("<hx-partial hx-target=\"#hero-stats\"");
		assertThat(body).doesNotContain("hx-get=\"/fragments/repositories/stats\"");
	}

	@Test
	void tokenPagesDoNotComputeRepositoryStatistics() throws Exception {
		bodyOf("/token");
		bodyOf("/app/token/form");
		verify(this.storageService, never()).stats(anyString());
	}

	@Test
	void homePageShowsDefaultKeyWarningWhenBuiltInPemIsUsed() throws Exception {
		String body = bodyOf("/");
		assertThat(body).contains("built-in default PEM key pair");
	}

	@Test
	void tokenPageShowsDefaultKeyWarningWhenBuiltInPemIsUsed() throws Exception {
		String body = bodyOf("/token");
		assertThat(body).contains("built-in default PEM key pair");
	}

	@Test
	void browsePageShowsEntries() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");
		String body = bodyOf("/browse/test-repo/org");
		assertThat(body).contains("test-repo");
		assertThat(body).contains("springframework");
		// The parent directory row is present for a non-root path
		assertThat(body).contains("../ Parent directory");
	}

	@Test
	void browsePageOfUnknownRepositoryShowsError() throws Exception {
		String body = bodyOf("/browse/unknown-repo");
		assertThat(body).contains("Failed to browse directory:");
	}

	@Test
	void entriesFragmentListsEntries() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");
		String body = bodyOf("/fragments/repositories/test-repo/entries", "path", "org/springframework");
		assertThat(body).contains("test-file.jar");
		assertThat(body).doesNotContain("This directory is empty.");
	}

	@Test
	void entriesFragmentOfUnknownRepositoryShowsError() throws Exception {
		String body = bodyOf("/fragments/repositories/unknown-repo/entries");
		assertThat(body).contains("Failed to browse directory:");
	}

	@Test
	void fileInfoFragmentShowsDetails() throws Exception {
		seed("test.jar", "test content");
		seed("test.jar.sha1", "abc123");
		seed("test.jar.sha256", "def456\n");
		String body = bodyOf("/fragments/repositories/test-repo/info", "path", "test.jar");
		assertThat(body).contains("File Information / test.jar");
		assertThat(body).contains("application/java-archive");
		assertThat(body).contains("abc123");
		assertThat(body).contains("def456");
	}

	@BeforeEach
	void resetCosignControlFiles() throws IOException {
		Path controlDir = Path.of("/tmp/kagami-cosign-mock");
		Files.createDirectories(controlDir);
		try (Stream<Path> files = Files.list(controlDir)) {
			files.forEach(path -> {
				try {
					Files.delete(path);
				}
				catch (IOException e) {
					throw new IllegalStateException(e);
				}
			});
		}
	}

	@Test
	void fileInfoFragmentShowsSigstoreBundleWhenPresent() throws Exception {
		seed("att.jar", "dummy jar content");
		seed("att.jar.attestation.sigstore.json",
				"{\"mediaType\":\"application/vnd.dev.sigstore.bundle+json;version=0.3\"}");

		String body = bodyOf("/fragments/repositories/test-repo/info", "path", "att.jar");
		assertThat(body).contains("Sigstore Attestation");
		assertThat(body).contains("att.jar.attestation.sigstore.json");
		assertThat(body).contains("href=\"/artifacts/test-repo/att.jar.attestation.sigstore.json\"");
		// Each bundle offers a verify action posting to the verification fragment
		assertThat(body).contains(
				"hx-post=\"/fragments/repositories/test-repo/verify-sigstore?path&#x3D;att.jar&amp;bundle&#x3D;att.jar.attestation.sigstore.json\"");
		assertThat(body).contains("Verify with cosign");
		// The pinned public key is offered as a download for the equivalent command
		assertThat(body).contains("href=\"/repositories/test-repo/sigstore/public-key\"");
		assertThat(body).contains("kagami-public.pem");

		// A file without a bundle does not show the section
		seed("bare.jar", "dummy jar content");
		String bare = bodyOf("/fragments/repositories/test-repo/info", "path", "bare.jar");
		assertThat(bare).doesNotContain("Sigstore Attestation");
	}

	@Test
	void verifySigstoreFragmentShowsSuccess() throws Exception {
		seed("att.jar", "dummy jar content");
		seed("att.jar.attestation.sigstore.json",
				"{\"mediaType\":\"application/vnd.dev.sigstore.bundle+json;version=0.3\"}");

		String body = this.mockMvc
			.perform(post("/fragments/repositories/test-repo/verify-sigstore").param("path", "att.jar")
				.param("bundle", "att.jar.attestation.sigstore.json")
				.with(csrf()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("Verified OK");
		assertThat(body).contains("mock cosign stdout");
	}

	@Test
	void verifySigstoreFragmentShowsFailure() throws Exception {
		seed("att.jar", "dummy jar content");
		seed("att.jar.attestation.sigstore.json",
				"{\"mediaType\":\"application/vnd.dev.sigstore.bundle+json;version=0.3\"}");
		Files.writeString(Path.of("/tmp/kagami-cosign-mock/exit-code"), "1");

		String body = this.mockMvc
			.perform(post("/fragments/repositories/test-repo/verify-sigstore").param("path", "att.jar")
				.param("bundle", "att.jar.attestation.sigstore.json")
				.with(csrf()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("Verification failed");
		assertThat(body).contains("cosign exited with code 1");
		assertThat(body).contains("mock cosign stderr");
	}

	@Test
	void verifySigstoreFragmentShowsErrorWhenTheBundleIsMissing() throws Exception {
		// A unique path: the storage is shared by every test in this class
		seed("lonely.jar", "dummy jar content");

		String body = this.mockMvc
			.perform(post("/fragments/repositories/test-repo/verify-sigstore").param("path", "lonely.jar")
				.param("bundle", "lonely.jar.attestation.sigstore.json")
				.with(csrf()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("Verification failed");
		assertThat(body).contains("Bundle not found in storage");
	}

	@Test
	void fileInfoFragmentOfMissingFileIsRejected() throws Exception {
		this.mockMvc.perform(get("/fragments/repositories/test-repo/info").param("path", "nonexistent.jar"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void pinnedPublicKeyIsDownloadable() throws Exception {
		this.mockMvc.perform(get("/repositories/test-repo/sigstore/public-key"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"kagami-public.pem\""))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("BEGIN PUBLIC KEY")));
	}

	@Test
	void publicKeyOfUnknownRepositoryIsNotFound() throws Exception {
		this.mockMvc.perform(get("/repositories/unknown-repo/sigstore/public-key")).andExpect(status().isNotFound());
	}

	@Test
	void configDialogFragmentShowsConfiguration() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");
		String body = bodyOf("/fragments/repositories/test-repo/config");
		assertThat(body).contains("Repository Configuration / test-repo");
		assertThat(body).contains("$HOME/.m2/settings.xml");
		assertThat(body).contains("$HOME/.gradle/init.gradle.kts");
		assertThat(body).contains("Usage Notes");
		verify(this.storageService, never()).stats(anyString());
	}

}
