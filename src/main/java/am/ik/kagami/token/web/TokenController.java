package am.ik.kagami.token.web;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.token.KagamiJwtClaims;
import am.ik.kagami.token.TokenSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class TokenController {

	private final KagamiProperties properties;

	private final TokenSigner tokenSigner;

	private final InstantSource instantSource;

	public TokenController(KagamiProperties properties, TokenSigner tokenSigner, InstantSource instantSource) {
		this.properties = properties;
		this.tokenSigner = tokenSigner;
		this.instantSource = instantSource;
	}

	@PostMapping(path = "/token")
	public String generateToken(@RequestParam(name = "expires_in", defaultValue = "3") long expiresIn,
			@RequestParam(defaultValue = "") List<String> repositories,
			@RequestParam(defaultValue = "") Set<String> scope, UriComponentsBuilder builder) {
		String issuer = builder.path("").build().toString();
		Instant issueAt = this.instantSource.instant();
		Instant expiresAt = issueAt.plus(expiresIn, ChronoUnit.HOURS);
		JWTClaimsSet claimsSet = new JWTClaimsSet.Builder().expirationTime(Date.from(expiresAt))
			.issuer(issuer)
			.audience("kagami")
			.issueTime(Date.from(issueAt))
			.claim(OAuth2ParameterNames.SCOPE, scope)
			.claim(KagamiJwtClaims.REPOSITORIES, repositories)
			.build();
		return this.tokenSigner.sign(claimsSet).serialize();
	}

	@GetMapping(path = "/token")
	public Object verifyToken(@AuthenticationPrincipal Jwt jwt) {
		return jwt.getClaims();
	}

}
