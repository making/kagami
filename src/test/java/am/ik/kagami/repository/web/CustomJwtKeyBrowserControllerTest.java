package am.ik.kagami.repository.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the browser API tests against the local file system storage with a generated
 * (non-built-in) JWT key pair: the home page must not show the default key warning.
 */
class CustomJwtKeyBrowserControllerTest extends RepositoryControllerTestBase {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8));
		String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + encoder.encodeToString(keyPair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----\n";
		String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
				+ encoder.encodeToString(keyPair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
		Path publicKey = Files.writeString(tempDir.resolve("kagami-public.pem"), publicKeyPem);
		Path privateKey = Files.writeString(tempDir.resolve("kagami-private.pem"), privateKeyPem);
		registry.add("kagami.jwt.public-key", () -> "file:" + publicKey);
		registry.add("kagami.jwt.private-key", () -> "file:" + privateKey);
	}

	@Test
	@Override
	void homePageShowsDefaultKeyWarningWhenBuiltInPemIsUsed() throws Exception {
		String body = bodyOf("/");
		assertThat(body).doesNotContain("built-in default PEM key pair");
	}

	@Test
	@Override
	void tokenPageShowsDefaultKeyWarningWhenBuiltInPemIsUsed() throws Exception {
		String body = bodyOf("/token");
		assertThat(body).doesNotContain("built-in default PEM key pair");
	}

}
