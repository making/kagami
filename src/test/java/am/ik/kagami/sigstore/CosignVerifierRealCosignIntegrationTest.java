package am.ik.kagami.sigstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip verification against a real cosign binary, when one is available: a key pair
 * is generated with cosign itself, the artifact is attested with
 * {@code cosign attest-blob}, and {@link CosignVerifier} has to accept the genuine bundle
 * and reject a tampered artifact.
 */
@SpringBootTest(properties = "kagami.repositories.sig-repo.url=https://repo.example.com/maven2")
@EnabledIf("cosignAvailable")
class CosignVerifierRealCosignIntegrationTest {

	static final String SLSAPROVENANCE1_PREDICATE = """
			{
			  "buildDefinition": {
			    "externalParameters": {"workflow": {}},
			    "internalParameters": {},
			    "resolvedDependencies": []
			  },
			  "runDetails": {
			    "builder": {"id": "https://example.com/builder"},
			    "byproducts": [],
			    "metadata": {}
			  }
			}""";

	@TempDir
	static Path workspace;

	@Autowired
	CosignVerifier cosignVerifier;

	@Autowired
	StorageService storageService;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> workspace.resolve("storage").toString());
		registry.add("kagami.repositories.sig-repo.sigstore.public-key-url",
				() -> workspace.resolve("keys.pub").toUri().toString());
		// Pin the real binary: a cosign embedded on the test classpath would otherwise
		// take precedence over the PATH one
		registry.add("kagami.sigstore.cosign-path", CosignVerifierRealCosignIntegrationTest::realCosignPath);
	}

	private static String realCosignPath() {
		try {
			Process process = new ProcessBuilder("which", "cosign").start();
			String path = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			process.waitFor(10, TimeUnit.SECONDS);
			if (process.exitValue() == 0 && !path.isEmpty()) {
				return path;
			}
		}
		catch (Exception e) {
			// fall through to the default
		}
		return CosignVerifier.DEFAULT_COSIGN;
	}

	static boolean cosignAvailable() {
		try {
			Process process = new ProcessBuilder("cosign", "version").start();
			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (Exception e) {
			return false;
		}
	}

	@Test
	void aGenuineBundleVerifiesAndATamperedArtifactDoesNot() throws Exception {
		cosign("generate-key-pair", "--output-key-prefix", workspace.resolve("keys").toString());
		String artifactContent = "genuine artifact content";
		Path artifactFile = workspace.resolve("app-1.0.jar");
		Files.writeString(artifactFile, artifactContent);
		Path bundleFile = workspace.resolve("app-1.0.jar.attestation.sigstore.json");
		Path predicateFile = workspace.resolve("predicate.json");
		Files.writeString(predicateFile, SLSAPROVENANCE1_PREDICATE);
		cosign("attest-blob", "--yes", "--key", workspace.resolve("keys.key").toString(), "--type", "slsaprovenance1",
				"--predicate", predicateFile.toString(), "--bundle", bundleFile.toString(), artifactFile.toString());
		String bundle = Files.readString(bundleFile);

		seed("org/example/app/1.0/app-1.0.jar", artifactContent);
		seed("org/example/app/1.0/app-1.0.jar.attestation.sigstore.json", bundle);

		CosignVerifier.VerificationResult result = this.cosignVerifier.verify("sig-repo",
				"org/example/app/1.0/app-1.0.jar", "org/example/app/1.0/app-1.0.jar.attestation.sigstore.json");

		assertThat(result.verified()).isTrue();
		// cosign prints "Verified OK" to stderr, alongside its warnings
		assertThat(result.stderr()).contains("Verified OK");

		// A modified artifact no longer matches the digest in the statement
		seed("org/example/app/1.0/app-1.0.jar", "tampered artifact content");
		CosignVerifier.VerificationResult tampered = this.cosignVerifier.verify("sig-repo",
				"org/example/app/1.0/app-1.0.jar", "org/example/app/1.0/app-1.0.jar.attestation.sigstore.json");

		assertThat(tampered.verified()).isFalse();
		assertThat(tampered.stderr()).contains("does not match");
	}

	void seed(String path, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			this.storageService.store(new ArtifactLocation("sig-repo", path), inputStream);
		}
	}

	private static void cosign(String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("cosign");
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
		// An empty password keeps the generated key unencrypted
		builder.environment().put("COSIGN_PASSWORD", "");
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
			throw new IllegalStateException("cosign " + String.join(" ", args) + " failed: " + output);
		}
	}

}
