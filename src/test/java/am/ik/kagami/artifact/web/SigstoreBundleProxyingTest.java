package am.ik.kagami.artifact.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for proxying sigstore attestation bundles distributed by the upstream
 * as sidecar files of artifacts (e.g. {@code <artifact>.attestation.sigstore.json}). The
 * upstream is a {@link MockServer} configured as the {@code sig-repo} repository with
 * sigstore sidecar fetching enabled and Basic authentication, so every
 * backend-independent expectation holds.
 */
@SpringBootTest(properties = { "spring.security.user.name=test-user", "spring.security.user.password=test-password",
		"logging.level.am.ik.kagami=DEBUG" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password",
		authorities = { "artifacts:read", "artifacts:delete" })
class SigstoreBundleProxyingTest {

	static final String BUNDLE = """
			{"mediaType":"application/vnd.dev.sigstore.bundle+json;version=0.3"}""";

	static final AtomicReference<String> authorization = new AtomicReference<>();

	static final MockServer mockServer = startMockServer();

	@TempDir
	static Path tempDir;

	@Autowired
	private MockMvc mockMvc;

	static MockServer startMockServer() {
		MockServer mockServer = new MockServer(TestSocketUtils.findAvailableTcpPort());
		mockServer.addFilter(new Filter() {
			@Override
			public void doFilter(HttpExchange exchange, Chain chain) throws java.io.IOException {
				authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
				chain.doFilter(exchange);
			}

			@Override
			public String description() {
				return "capture Authorization header";
			}
		});
		mockServer.run();
		return mockServer;
	}

	@AfterAll
	static void stopMockServer() {
		mockServer.close();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		registry.add("kagami.repositories.sig-repo.url", () -> "http://localhost:%d".formatted(mockServer.port()));
		registry.add("kagami.repositories.sig-repo.username", () -> "repo-user");
		registry.add("kagami.repositories.sig-repo.password", () -> "repo-pass");
		registry.add("kagami.repositories.sig-repo.sigstore.enabled", () -> "true");
	}

	@Test
	void bundleIsStoredWithTheArtifactAndDownloadableThroughTheArtifactPath() throws Exception {
		mockServer.GET("/org/example/lib/1.0/lib-1.0.jar", req -> Response.ok("jar content"))
			.GET("/org/example/lib/1.0/lib-1.0.jar.attestation.sigstore.json", req -> Response.json(BUNDLE));

		this.mockMvc.perform(get("/artifacts/sig-repo/org/example/lib/1.0/lib-1.0.jar")).andExpect(status().isOk());

		// The bundle is fetched from the upstream with the repository credentials and
		// stored next to the artifact
		assertThat(authorization.get()).startsWith("Basic ");
		Path storedBundle = tempDir.resolve("sig-repo/org/example/lib/1.0/lib-1.0.jar.attestation.sigstore.json");
		assertThat(storedBundle).exists();
		assertThat(Files.readString(storedBundle)).isEqualTo(BUNDLE);

		this.mockMvc.perform(get("/artifacts/sig-repo/org/example/lib/1.0/lib-1.0.jar.attestation.sigstore.json"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/json"))
			.andExpect(content().string(BUNDLE))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
					"inline;filename=lib-1.0.jar.attestation.sigstore.json"));
	}

	@Test
	void artifactResponseIsUnaffectedWhenTheBundleIsAbsent() throws Exception {
		// No bundle sidecar is served for this artifact: both configured suffixes 404
		mockServer.GET("/org/example/bare/1.0/bare-1.0.jar", req -> Response.ok("jar content"));

		this.mockMvc.perform(get("/artifacts/sig-repo/org/example/bare/1.0/bare-1.0.jar"))
			.andExpect(status().isOk())
			.andExpect(content().string("jar content"));

		assertThat(tempDir.resolve("sig-repo/org/example/bare/1.0/bare-1.0.jar.attestation.sigstore.json"))
			.doesNotExist();
		assertThat(tempDir.resolve("sig-repo/org/example/bare/1.0/bare-1.0.jar.sigstore.json")).doesNotExist();

		this.mockMvc.perform(get("/artifacts/sig-repo/org/example/bare/1.0/bare-1.0.jar.attestation.sigstore.json"))
			.andExpect(status().isNotFound());
	}

	@Test
	void jsonAndSignatureFilesAreServedInline() throws Exception {
		Files.createDirectories(tempDir.resolve("sig-repo/org/example"));
		Files.writeString(tempDir.resolve("sig-repo/org/example/lib-1.0.jar.sigstore.json"), BUNDLE);
		Files.writeString(tempDir.resolve("sig-repo/org/example/lib-1.0.jar.asc"), "-----BEGIN PGP SIGNATURE-----");

		this.mockMvc.perform(get("/artifacts/sig-repo/org/example/lib-1.0.jar.sigstore.json"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=lib-1.0.jar.sigstore.json"));

		this.mockMvc.perform(get("/artifacts/sig-repo/org/example/lib-1.0.jar.asc"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=lib-1.0.jar.asc"));
	}

}
