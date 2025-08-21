package am.ik.kagami;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.io.ApplicationResourceLoader;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

/**
 * Configuration properties for Kagami mirror server
 */
@ConfigurationProperties(prefix = "kagami")
public record KagamiProperties(@DefaultValue Storage storage, @DefaultValue Map<String, Repository> repositories,
		Proxy proxy, @DefaultValue Jwt jwt, @DefaultValue Authentication authentication) {

	public record Storage(String path) {
	}

	public record Repository(String url, String username, String password, @DefaultValue("false") boolean isPrivate) {
	}

	public record Proxy(String url) {
	}

	public static class Jwt {

		private final RSAPublicKey publicKey;

		private final RSAPrivateKey privateKey;

		// to support `base64:` prefix
		private static final ResourceLoader resourceLoader = ApplicationResourceLoader.get();

		public Jwt(String publicKey, String privateKey) {
			this.publicKey = publicKey == null ? null : resourceToPublicKey(resourceLoader.getResource(publicKey));
			this.privateKey = privateKey == null ? null : resourceToPrivateKey(resourceLoader.getResource(privateKey));
		}

		public RSAPublicKey publicKey() {
			return publicKey;
		}

		public RSAPrivateKey privateKey() {
			return privateKey;
		}

		public String keyId() {
			byte[] publicKeyDERBytes = publicKey.getEncoded();
			try {
				MessageDigest hasher = MessageDigest.getInstance("SHA-256");
				byte[] publicKeyDERHash = hasher.digest(publicKeyDERBytes);
				return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyDERHash);
			}
			catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}

		static RSAPublicKey resourceToPublicKey(Resource resource) {
			try (InputStream stream = resource.getInputStream()) {
				byte[] content = Base64.getDecoder()
					.decode(StreamUtils.copyToString(stream, StandardCharsets.UTF_8)
						.replace("-----BEGIN PUBLIC KEY-----", "")
						.replace("-----END PUBLIC KEY-----", "")
						.replace("\n", ""));
				X509EncodedKeySpec spec = new X509EncodedKeySpec(content);
				return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}

		static RSAPrivateKey resourceToPrivateKey(Resource resource) {
			try (InputStream stream = resource.getInputStream()) {
				return (RSAPrivateKey) PemContent.of(StreamUtils.copyToString(stream, StandardCharsets.UTF_8))
					.getPrivateKey();
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

	}

	public record Authentication(@DefaultValue("simple") AuthenticationType type,
			@DefaultValue(".*") List<Pattern> allowedNamePatterns) {
	}

	public enum AuthenticationType {

		SIMPLE, OIDC

	}
}