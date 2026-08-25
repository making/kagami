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
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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
		@Nullable Proxy proxy, @DefaultValue Jwt jwt, @DefaultValue Authentication authentication) {

	public record Storage(String path) {
	}

	public record Repository(String url, @Nullable String username, @Nullable String password,
			@DefaultValue("false") boolean isPrivate) {
	}

	/**
	 * HTTP proxy settings for outgoing connections to remote repositories.
	 *
	 * @param url proxy used for {@code http} repositories and, unless {@code httpsUrl} is
	 * set, for {@code https} repositories as well
	 * @param httpsUrl proxy used for {@code https} repositories
	 * @param username user name for proxy authentication, unless the credentials are
	 * embedded in the proxy URL
	 * @param password password for proxy authentication, unless the credentials are
	 * embedded in the proxy URL
	 * @param nonProxyHosts hosts that must be reached without going through the proxy
	 */
	public record Proxy(@Nullable String url, @Nullable String httpsUrl, @Nullable String username,
			@Nullable String password, @DefaultValue List<String> nonProxyHosts) {
	}

	public static class Jwt {

		private final @Nullable RSAPublicKey publicKey;

		private final @Nullable RSAPrivateKey privateKey;

		// to support `base64:` prefix
		private static final ResourceLoader resourceLoader = ApplicationResourceLoader.get();

		public Jwt(@Nullable String publicKey, @Nullable String privateKey) {
			this.publicKey = publicKey == null ? null : resourceToPublicKey(resourceLoader.getResource(publicKey));
			this.privateKey = privateKey == null ? null : resourceToPrivateKey(resourceLoader.getResource(privateKey));
		}

		public RSAPublicKey publicKey() {
			return Objects.requireNonNull(this.publicKey, "'kagami.jwt.public-key' is not configured");
		}

		public RSAPrivateKey privateKey() {
			return Objects.requireNonNull(this.privateKey, "'kagami.jwt.private-key' is not configured");
		}

		public String keyId() {
			byte[] publicKeyDERBytes = publicKey().getEncoded();
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
				PemContent pemContent = Objects.requireNonNull(
						PemContent.of(StreamUtils.copyToString(stream, StandardCharsets.UTF_8)),
						"No PEM content found in " + resource);
				return (RSAPrivateKey) Objects.requireNonNull(pemContent.getPrivateKey(),
						"No private key found in " + resource);
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