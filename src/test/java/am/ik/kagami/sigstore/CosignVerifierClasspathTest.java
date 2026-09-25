package am.ik.kagami.sigstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
 * Verifies that a cosign binary configured as a {@code classpath:} location — the way the
 * {@code embedded-cosign} Maven profile ships it inside the application jar — is
 * extracted to an executable temporary file and used for the verification.
 */
@SpringBootTest(properties = { "kagami.repositories.sig-repo.url=https://repo.example.com/maven2",
		"kagami.repositories.sig-repo.sigstore.public-key-url=classpath:kagami-public.pem",
		"kagami.sigstore.cosign-path=classpath:cosign-mock.sh" })
class CosignVerifierClasspathTest {

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
	void classpathCosignIsExtractedAndExecuted() throws Exception {
		String base = "org/example/classpath/1.0/app-1.0.jar";
		seed("sig-repo", base, "dummy jar content");
		seed("sig-repo", base + ".attestation.sigstore.json", "{\"bundle\":true}");

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		// The mock cosign ran: the verification succeeded and the arguments were recorded
		assertThat(result.verified()).isTrue();
		assertThat(result.error()).isNull();
		List<String> args = Files.readAllLines(CONTROL_DIR.resolve("args"));
		assertThat(args).contains("verify-blob-attestation");
	}

	@Test
	void extractedBinaryIsExecutableAndReused() throws Exception {
		String base = "org/example/reuse/1.0/app-1.0.jar";
		seed("sig-repo", base, "dummy jar content");
		seed("sig-repo", base + ".attestation.sigstore.json", "{\"bundle\":true}");

		this.cosignVerifier.verify("sig-repo", base, base + ".attestation.sigstore.json");
		List<Path> afterFirst = extractedTempFiles();
		this.cosignVerifier.verify("sig-repo", base, base + ".attestation.sigstore.json");
		List<Path> afterSecond = extractedTempFiles();

		// The cache returns the same file: no additional extraction happened
		assertThat(afterFirst).isNotEmpty();
		assertThat(afterSecond).isEqualTo(afterFirst);
		assertThat(Files.getPosixFilePermissions(afterFirst.getFirst())).contains(PosixFilePermission.OWNER_EXECUTE,
				PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
	}

	private static List<Path> extractedTempFiles() throws IOException {
		try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
			return files.filter(path -> path.getFileName().toString().startsWith("kagami-cosign")).toList();
		}
	}

	void seed(String repositoryId, String path, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation(repositoryId, path), inputStream);
		}
	}

}
