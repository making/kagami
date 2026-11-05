package am.ik.kagami.token.web;

import am.ik.kagami.token.KagamiJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriUtils;

@Component
public class RepositoryTokenValidator implements OAuth2TokenValidator<Jwt> {

	private static final String ARTIFACTS_PREFIX = "/artifacts/";

	private final HttpServletRequest request;

	private final AntPathMatcher matcher = new AntPathMatcher();

	public RepositoryTokenValidator(HttpServletRequest request) {
		this.request = request;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt token) {
		String repositoryId = repositoryId(this.request.getRequestURI());
		if (repositoryId == null) {
			return OAuth2TokenValidatorResult.success();
		}
		if (!token.hasClaim(KagamiJwtClaims.REPOSITORIES)) {
			return OAuth2TokenValidatorResult.failure(new OAuth2Error(BearerTokenErrorCodes.INVALID_TOKEN,
					"Token does not contain '%s' claim".formatted(KagamiJwtClaims.REPOSITORIES), null));
		}
		List<String> allowedRepositories = token.getClaimAsStringList(KagamiJwtClaims.REPOSITORIES);
		boolean isValidRepository = allowedRepositories != null
				&& allowedRepositories.stream().anyMatch(repo -> this.matcher.match(repo, repositoryId));
		if (!isValidRepository) {
			return OAuth2TokenValidatorResult.failure(new OAuth2Error(BearerTokenErrorCodes.INSUFFICIENT_SCOPE,
					"Token does not contain the repository '%s' in '%s' claim".formatted(repositoryId,
							KagamiJwtClaims.REPOSITORIES),
					null));
		}
		return OAuth2TokenValidatorResult.success();
	}

	/**
	 * Resolves the repository id addressed by the request. The raw request URI is decoded
	 * and its dot segments resolved before the id is taken, so that a percent-encoded or
	 * dot-segment spelling cannot address a repository other than the one that is
	 * checked.
	 * @param requestUri the raw request URI
	 * @return the repository id, or {@code null} when the request does not address an
	 * artifact
	 */
	static @Nullable String repositoryId(String requestUri) {
		String path = normalize(decode(requestUri));
		if (!path.startsWith(ARTIFACTS_PREFIX)) {
			return null;
		}
		String remainder = path.substring(ARTIFACTS_PREFIX.length());
		int separator = remainder.indexOf('/');
		String repositoryId = separator < 0 ? remainder : remainder.substring(0, separator);
		return repositoryId.isEmpty() ? null : repositoryId;
	}

	private static String decode(String requestUri) {
		try {
			return UriUtils.decode(requestUri, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return requestUri;
		}
	}

	private static String normalize(String path) {
		Deque<String> segments = new ArrayDeque<>();
		for (String segment : path.split("/")) {
			if (segment.isEmpty() || ".".equals(segment)) {
				continue;
			}
			if ("..".equals(segment)) {
				segments.pollLast();
			}
			else {
				segments.addLast(segment);
			}
		}
		return "/" + String.join("/", segments);
	}

}
