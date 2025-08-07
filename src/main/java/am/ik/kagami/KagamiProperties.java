package am.ik.kagami;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Kagami mirror server
 */
@ConfigurationProperties(prefix = "kagami")
public record KagamiProperties(Storage storage, Map<String, Repository> repositories, Proxy proxy) {

	public record Storage(String path) {
	}

	public record Repository(String url, String username, String password) {
	}

	public record Proxy(String url) {
	}

}