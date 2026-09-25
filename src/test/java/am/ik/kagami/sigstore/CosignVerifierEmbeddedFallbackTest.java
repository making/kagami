package am.ik.kagami.sigstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that with {@code kagami.sigstore.cosign-path} left at its default, a cosign
 * binary embedded in the application jar ({@code cosign/cosign} on the classpath) takes
 * precedence over the one on the {@code PATH}. This is how the Docker image built with
 * the {@code embedded-cosign} Maven profile ships cosign.
 */
@SpringBootTest(properties = { "kagami.repositories.sig-repo.url=https://repo.example.com/maven2",
		"kagami.repositories.sig-repo.sigstore.public-key-url=classpath:kagami-public.pem" })
class CosignVerifierEmbeddedFallbackTest {

	static final Path CONTROL_DIR = Path.of("/tmp/kagami-cosign-mock");

	@TempDir
	static Path tempDir;

	@Autowired
	CosignVerifier cosignVerifier;

	@Autowired
	StorageService storageService;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@BeforeEach
	void resetControlFiles() throws IOException {
		Files.createDirectories(CONTROL_DIR);
		try (var files = Files.list(CONTROL_DIR)) {
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
	void embeddedCosignIsPreferredOverThePath() throws Exception {
		String base = "org/example/fallback/1.0/app-1.0.jar";
		seed("sig-repo", base, "dummy jar content");
		seed("sig-repo", base + ".attestation.sigstore.json", "{\"bundle\":true}");

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		// The embedded mock cosign ran, not a real cosign from the PATH
		assertThat(result.verified()).isTrue();
		assertThat(result.stdout()).contains("mock cosign stdout");
		List<String> args = Files.readAllLines(CONTROL_DIR.resolve("args"));
		assertThat(args).contains("verify-blob-attestation");
	}

	void seed(String repositoryId, String path, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation(repositoryId, path), inputStream);
		}
	}

}
