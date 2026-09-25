package am.ik.kagami.sigstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 * Tests {@link CosignVerifier} against a mock cosign script, so the command assembly, the
 * exit code decision and the error paths are covered without a real cosign installation.
 * <p>
 * The storage is shared by every test in this class, so each test works on its own
 * artifact path.
 */
@SpringBootTest(properties = { "kagami.repositories.sig-repo.url=https://repo.example.com/maven2",
		"kagami.repositories.sig-repo.sigstore.public-key-url=classpath:kagami-public.pem",
		"kagami.repositories.keyless-repo.url=https://repo.example.com/maven2",
		"kagami.repositories.keyless-repo.sigstore.verification=keyless",
		"kagami.repositories.keyless-repo.sigstore.certificate-identity-regexp=https://github.com/example/.*/.*",
		"kagami.repositories.keyless-repo.sigstore.certificate-oidc-issuer=https://token.actions.githubusercontent.com",
		"kagami.repositories.keyless-bare-repo.url=https://repo.example.com/maven2",
		"kagami.repositories.keyless-bare-repo.sigstore.verification=keyless",
		"kagami.repositories.nokey-repo.url=https://repo.example.com/maven2" })
class CosignVerifierTest {

	/** Content every seeded artifact carries; its digest is asserted in the arguments. */
	static final String ARTIFACT_CONTENT = "dummy jar content";

	static final String BUNDLE_CONTENT = "{\"bundle\":true}";

	static final Path MOCK_COSIGN = mockCosignPath();

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
		registry.add("kagami.sigstore.cosign-path", () -> MOCK_COSIGN.toString());
		// Short enough to keep the timeout test quick, long enough for the mock script
		registry.add("kagami.sigstore.timeout", () -> "3s");
	}

	@BeforeEach
	void resetControlFiles() throws IOException {
		Files.createDirectories(CONTROL_DIR);
		try (var files = Files.list(CONTROL_DIR)) {
			files.forEach(path -> delete(path));
		}
	}

	void seed(String repositoryId, String path, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation(repositoryId, path), inputStream);
		}
	}

	@Test
	void successfulVerificationRunsCosignWithTheExpectedArguments() throws Exception {
		String base = "org/example/success/1.0/app-1.0.jar";
		seed("sig-repo", base, ARTIFACT_CONTENT);
		seed("sig-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isTrue();
		assertThat(result.error()).isNull();
		assertThat(result.stdout()).contains("mock cosign stdout");
		assertThat(result.stderr()).contains("mock cosign stderr");
		// The mock script records one argument per line
		List<String> args = Files.readAllLines(CONTROL_DIR.resolve("args"));
		assertThat(args).containsSubsequence("verify-blob-attestation", "--digest", sha256(ARTIFACT_CONTENT),
				"--digestAlg", "sha256", "--type", "slsaprovenance1", "--key", "--insecure-ignore-tlog=true");
		// The bundle and the public key are handed over as temporary files that no
		// longer exist once the verification finished
		int bundleFlag = args.indexOf("--bundle");
		assertThat(args.get(bundleFlag + 1)).doesNotContain(tempDir.toString());
		int keyFlag = args.indexOf("--key");
		assertThat(args.get(keyFlag + 1)).doesNotContain(tempDir.toString());
		assertThat(args).doesNotContain("--certificate-identity-regexp");
	}

	@Test
	void nonZeroExitCodeIsReportedAsAFailure() throws Exception {
		String base = "org/example/failure/1.0/app-1.0.jar";
		seed("sig-repo", base, ARTIFACT_CONTENT);
		seed("sig-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);
		Files.writeString(CONTROL_DIR.resolve("exit-code"), "1");

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isFalse();
		assertThat(result.exitCode()).isEqualTo(1);
		assertThat(result.stderr()).contains("mock cosign stderr");
	}

	@Test
	void missingArtifactIsReported() throws Exception {
		String base = "org/example/no-artifact/1.0/app-1.0.jar";
		seed("sig-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isFalse();
		assertThat(result.exitCode()).isNull();
		assertThat(result.error()).contains("Artifact not found in storage").contains("garbage collected");
	}

	@Test
	void missingBundleIsReported() throws Exception {
		String base = "org/example/no-bundle/1.0/app-1.0.jar";
		seed("sig-repo", base, ARTIFACT_CONTENT);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isFalse();
		assertThat(result.error()).contains("Bundle not found in storage");
	}

	@Test
	void unknownRepositoryIsReported() {
		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("unknown-repo", "a.jar", "a.jar.sig");

		assertThat(result.verified()).isFalse();
		assertThat(result.error()).contains("Repository not found: unknown-repo");
	}

	@Test
	void keyModeWithoutPublicKeyUrlIsReported() throws Exception {
		String base = "org/example/no-key/1.0/app-1.0.jar";
		seed("nokey-repo", base, ARTIFACT_CONTENT);
		seed("nokey-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("nokey-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isFalse();
		assertThat(result.error()).contains("public-key-url").contains("nokey-repo");
	}

	@Test
	void keylessWithoutIdentityRegexpIsReported() throws Exception {
		String base = "org/example/keyless-bare/1.0/app-1.0.jar";
		seed("keyless-bare-repo", base, ARTIFACT_CONTENT);
		seed("keyless-bare-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("keyless-bare-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isFalse();
		assertThat(result.error()).contains("certificate-identity-regexp").contains("keyless");
	}

	@Test
	void keylessVerificationPassesIdentityConstraintsInsteadOfAKey() throws Exception {
		String base = "org/example/keyless/1.0/app-1.0.jar";
		seed("keyless-repo", base, ARTIFACT_CONTENT);
		seed("keyless-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("keyless-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isTrue();
		List<String> args = Files.readAllLines(CONTROL_DIR.resolve("args"));
		assertThat(args).containsSubsequence("--certificate-identity-regexp", "https://github.com/example/.*/.*",
				"--certificate-oidc-issuer", "https://token.actions.githubusercontent.com");
		assertThat(args).doesNotContain("--key");
		// Keyless verification keeps the transparency log check enabled
		assertThat(args).doesNotContain("--insecure-ignore-tlog");
	}

	@Test
	void commandLineMirrorsTheExecutedCommandForKeyVerification() {
		String command = this.cosignVerifier.commandLine("sig-repo",
				CosignVerifier.VerificationInputs.builder()
					.artifactFile("app-1.0.jar")
					.bundleFile("app-1.0.jar.attestation.sigstore.json")
					.digest("abc123")
					.keyFile("key.pub")
					.build());

		assertThat(command).isEqualTo("cosign verify-blob-attestation app-1.0.jar "
				+ "--bundle app-1.0.jar.attestation.sigstore.json --digest abc123 --digestAlg sha256 "
				+ "--type slsaprovenance1 --key key.pub --insecure-ignore-tlog=true");
		// The key URL is shown alongside the command so the key can be downloaded
		assertThat(this.cosignVerifier.publicKeyUrl("sig-repo")).isEqualTo("classpath:kagami-public.pem");
		assertThat(this.cosignVerifier.publicKeyUrl("keyless-repo")).isNull();
		assertThat(this.cosignVerifier.publicKeyUrl("unknown-repo")).isNull();
	}

	@Test
	void commandLineMirrorsTheExecutedCommandForKeylessVerification() {
		String command = this.cosignVerifier.commandLine("keyless-repo",
				CosignVerifier.VerificationInputs.builder()
					.artifactFile("app-1.0.jar")
					.bundleFile("app-1.0.jar.sigstore.json")
					.build());

		assertThat(command).isEqualTo("cosign verify-blob-attestation app-1.0.jar "
				+ "--bundle app-1.0.jar.sigstore.json --type slsaprovenance1 "
				+ "--certificate-identity-regexp 'https://github.com/example/.*/.*' "
				+ "--certificate-oidc-issuer https://token.actions.githubusercontent.com");
	}

	@Test
	void commandLineIsAbsentForAnUnknownRepositoryOrMissingTrustAnchor() {
		CosignVerifier.VerificationInputs inputs = CosignVerifier.VerificationInputs.builder()
			.artifactFile("a.jar")
			.bundleFile("a.jar.sig")
			.digest("abc123")
			.build();
		assertThat(this.cosignVerifier.commandLine("unknown-repo", inputs)).isNull();
		// keyless without a certificate identity regexp cannot be reproduced
		assertThat(this.cosignVerifier.commandLine("keyless-bare-repo", inputs)).isNull();
	}

	@Test
	void hangingCosignIsKilledAfterTheTimeout() throws Exception {
		String base = "org/example/timeout/1.0/app-1.0.jar";
		seed("sig-repo", base, ARTIFACT_CONTENT);
		seed("sig-repo", base + ".attestation.sigstore.json", BUNDLE_CONTENT);
		Files.writeString(CONTROL_DIR.resolve("sleep"), "yes");

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo", base,
				base + ".attestation.sigstore.json");

		assertThat(result.verified()).isFalse();
		assertThat(result.error()).contains("cosign timed out after 3s");
	}

	private static Path mockCosignPath() {
		try {
			return Path.of(CosignVerifierTest.class.getResource("/cosign-mock.sh").toURI());
		}
		catch (Exception e) {
			throw new IllegalStateException("cosign-mock.sh not found on the classpath", e);
		}
	}

	private static String sha256(String content) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void delete(Path path) {
		try {
			Files.delete(path);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
