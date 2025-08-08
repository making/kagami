package am.ik.kagami.token.web;

import am.ik.kagami.token.KagamiJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class RepositoryTokenValidator implements OAuth2TokenValidator<Jwt> {

	private final HttpServletRequest request;

	private final AntPathMatcher matcher = new AntPathMatcher();

	public RepositoryTokenValidator(HttpServletRequest request) {
		this.request = request;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt token) {
		String requestURI = request.getRequestURI();
		if (requestURI.contains("/artifacts")) {
			if (!token.hasClaim(KagamiJwtClaims.REPOSITORIES)) {
				return OAuth2TokenValidatorResult.failure(new OAuth2Error(BearerTokenErrorCodes.INVALID_TOKEN,
						"Token does not contain '%s' claim".formatted(KagamiJwtClaims.REPOSITORIES), null));
			}
			// extract repository id from the request URI that looks like
			// /artifacts/{repositoryId}/...
			String[] pathSegments = requestURI.split("/");
			if (pathSegments.length > 1) {
				String repositoryId = pathSegments[2];
				boolean isValidRepository = token.getClaimAsStringList(KagamiJwtClaims.REPOSITORIES)
					.stream()
					.anyMatch(repo -> matcher.match(repo, repositoryId));
				if (!isValidRepository) {
					return OAuth2TokenValidatorResult.failure(new OAuth2Error(BearerTokenErrorCodes.INSUFFICIENT_SCOPE,
							"Token does not contain the repository '%s' in '%s' claim".formatted(repositoryId,
									KagamiJwtClaims.REPOSITORIES),
							null));
				}
			}
		}
		return OAuth2TokenValidatorResult.success();
	}

}
