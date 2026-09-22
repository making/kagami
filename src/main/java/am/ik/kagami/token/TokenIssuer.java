package am.ik.kagami.token;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Service;

import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Issues signed Kagami JWT tokens. Shared by the machine-facing {@code POST /token}
 * endpoint and the web UI's token generation form.
 */
@Service
public class TokenIssuer {

	private final TokenSigner tokenSigner;

	private final InstantSource instantSource;

	public TokenIssuer(TokenSigner tokenSigner, InstantSource instantSource) {
		this.tokenSigner = tokenSigner;
		this.instantSource = instantSource;
	}

	/**
	 * Issue a signed token for the given request.
	 * @param request the token request
	 * @return the serialized JWT
	 */
	public String issue(TokenRequest request) {
		Instant issueAt = this.instantSource.instant();
		Instant expiresAt = issueAt.plus(request.expiresIn(), ChronoUnit.HOURS);
		JWTClaimsSet claimsSet = new JWTClaimsSet.Builder().expirationTime(Date.from(expiresAt))
			.subject(request.userName())
			.issuer(request.issuer())
			.audience("kagami")
			.issueTime(Date.from(issueAt))
			.claim(OAuth2ParameterNames.SCOPE, request.scope())
			.claim(KagamiJwtClaims.REPOSITORIES, request.repositories())
			.build();
		return this.tokenSigner.sign(claimsSet).serialize();
	}

	/**
	 * A token issuance request.
	 *
	 * @param issuer the issuer URL of this Kagami instance
	 * @param expiresIn the token validity in hours
	 * @param repositories the repositories the token grants access to
	 * @param scope the OAuth scopes the token grants
	 * @param userName the subject the token is issued to
	 */
	public record TokenRequest(String issuer, long expiresIn, List<String> repositories, Set<String> scope,
			String userName) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String issuer;

			private long expiresIn;

			private List<String> repositories = List.of();

			private Set<String> scope = Set.of();

			@Nullable private String userName;

			private Builder() {
			}

			public Builder issuer(String issuer) {
				this.issuer = issuer;
				return this;
			}

			public Builder expiresIn(long expiresIn) {
				this.expiresIn = expiresIn;
				return this;
			}

			public Builder repositories(List<String> repositories) {
				this.repositories = repositories;
				return this;
			}

			public Builder scope(Set<String> scope) {
				this.scope = scope;
				return this;
			}

			public Builder userName(String userName) {
				this.userName = userName;
				return this;
			}

			public TokenRequest build() {
				return new TokenRequest(Objects.requireNonNull(this.issuer, "issuer is required"), this.expiresIn,
						Objects.requireNonNull(this.repositories, "repositories is required"),
						Objects.requireNonNull(this.scope, "scope is required"),
						Objects.requireNonNull(this.userName, "userName is required"));
			}

		}

	}

}
