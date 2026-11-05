package am.ik.kagami.token;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
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

	/** The longest token validity accepted, in hours. */
	public static final long MAX_VALIDITY_HOURS = 24 * 365;

	private static final Duration MIN_VALIDITY = Duration.ofHours(1);

	private static final Duration MAX_VALIDITY = Duration.ofHours(MAX_VALIDITY_HOURS);

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
		Instant expiresAt = issueAt.plus(request.validity());
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
	 * @param validity the token validity
	 * @param repositories the repositories the token grants access to
	 * @param scope the OAuth scopes the token grants
	 * @param userName the subject the token is issued to
	 */
	public record TokenRequest(String issuer, Duration validity, List<String> repositories, Set<String> scope,
			String userName) {

		public TokenRequest {
			Objects.requireNonNull(issuer, "issuer is required");
			Objects.requireNonNull(validity, "validity is required");
			Objects.requireNonNull(repositories, "repositories is required");
			Objects.requireNonNull(scope, "scope is required");
			Objects.requireNonNull(userName, "userName is required");
			if (validity.compareTo(MIN_VALIDITY) < 0 || validity.compareTo(MAX_VALIDITY) > 0) {
				throw new IllegalArgumentException(
						"Token validity must be between 1 and %d hours.".formatted(MAX_VALIDITY_HOURS));
			}
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String issuer;

			@Nullable private Duration validity;

			private List<String> repositories = List.of();

			private Set<String> scope = Set.of();

			@Nullable private String userName;

			private Builder() {
			}

			public Builder issuer(String issuer) {
				this.issuer = issuer;
				return this;
			}

			public Builder validity(Duration validity) {
				this.validity = validity;
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
				return new TokenRequest(Objects.requireNonNull(this.issuer, "issuer is required"),
						Objects.requireNonNull(this.validity, "validity is required"), this.repositories, this.scope,
						Objects.requireNonNull(this.userName, "userName is required"));
			}

		}

	}

}
