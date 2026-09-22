package am.ik.kagami.token.web;

import java.util.List;
import java.util.Set;

import am.ik.kagami.token.TokenIssuer;
import am.ik.kagami.token.TokenIssuer.TokenRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class TokenController {

	private final TokenIssuer tokenIssuer;

	public TokenController(TokenIssuer tokenIssuer) {
		this.tokenIssuer = tokenIssuer;
	}

	@PostMapping(path = "/token")
	public String generateToken(@RequestParam(name = "expires_in", defaultValue = "3") long expiresIn,
			@RequestParam(defaultValue = "") List<String> repositories,
			@RequestParam(defaultValue = "") Set<String> scope, Authentication authentication,
			UriComponentsBuilder builder) {
		String issuer = builder.path("").build().toString();
		return this.tokenIssuer.issue(TokenRequest.builder()
			.issuer(issuer)
			.expiresIn(expiresIn)
			.repositories(repositories)
			.scope(scope)
			.userName(authentication.getName())
			.build());
	}

}
