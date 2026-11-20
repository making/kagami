package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties.Repository;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

/**
 * Applies the configured credentials of an upstream repository to an outbound request.
 * <p>
 * A bearer {@code token} takes precedence over Basic credentials because registries such
 * as Artifactory and GitHub Packages issue tokens rather than passwords. The client's own
 * {@code Authorization} header is never forwarded: it carries a Kagami JWT, not the
 * upstream credentials.
 */
public final class UpstreamAuthentication {

	private UpstreamAuthentication() {
	}

	/**
	 * Set the authentication header for the given repository, if any credentials are
	 * configured.
	 * @param headers the outbound request headers
	 * @param repository the repository configuration, or {@code null} when the repository
	 * is unknown
	 */
	public static void apply(HttpHeaders headers, @Nullable Repository repository) {
		if (repository == null) {
			return;
		}
		if (StringUtils.hasText(repository.token())) {
			headers.setBearerAuth(repository.token());
			return;
		}
		if (StringUtils.hasText(repository.username()) && StringUtils.hasText(repository.password())) {
			headers.setBasicAuth(repository.username(), repository.password());
		}
	}

}
