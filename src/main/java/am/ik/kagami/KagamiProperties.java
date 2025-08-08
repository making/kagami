package am.ik.kagami;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for Kagami mirror server
 */
@ConfigurationProperties(prefix = "kagami")
public record KagamiProperties(Storage storage, Map<String, Repository> repositories, Proxy proxy, Jwt jwt) {

	public record Storage(String path) {
	}

	public record Repository(String url, String username, String password, @DefaultValue("false") boolean isPrivate) {
	}

	public record Proxy(String url) {
	}

	public record Jwt(RSAPublicKey publicKey, RSAPrivateKey privateKey) {

	}

}