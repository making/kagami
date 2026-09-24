package am.ik.kagami.token.web;

import java.util.List;
import java.util.Set;

import am.ik.kagami.rbac.RbacService;
import am.ik.kagami.token.TokenIssuer;
import am.ik.kagami.token.TokenIssuer.TokenRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class TokenController {

	private final TokenIssuer tokenIssuer;

	private final RbacService rbacService;

	public TokenController(TokenIssuer tokenIssuer, RbacService rbacService) {
		this.tokenIssuer = tokenIssuer;
		this.rbacService = rbacService;
	}

	/**
	 * Issues a token. The requested scopes are capped at the scopes the principal may
	 * issue; a request beyond that cap is rejected with 403.
	 */
	@PostMapping(path = "/token")
	public ResponseEntity<String> generateToken(@RequestParam(name = "expires_in", defaultValue = "3") long expiresIn,
			@RequestParam(defaultValue = "") List<String> repositories,
			@RequestParam(defaultValue = "") Set<String> scope, Authentication authentication,
			UriComponentsBuilder builder) {
		if (authentication instanceof JwtAuthenticationToken) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		if (!this.rbacService.issuableScopes(authentication).containsAll(scope)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		String issuer = builder.path("").build().toString();
		String token = this.tokenIssuer.issue(TokenRequest.builder()
			.issuer(issuer)
			.expiresIn(expiresIn)
			.repositories(repositories)
			.scope(scope)
			.userName(authentication.getName())
			.build());
		return ResponseEntity.ok(token);
	}

}
