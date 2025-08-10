package am.ik.kagami;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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

	public record Jwt(RSAPublicKey publicKey, RSAPrivateKey privateKey) {

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

	}

	public record Authentication(@DefaultValue("simple") AuthenticationType type,
			@DefaultValue(".*") List<Pattern> allowedNamePatterns) {
	}

	public enum AuthenticationType {

		SIMPLE, OIDC

	}
}