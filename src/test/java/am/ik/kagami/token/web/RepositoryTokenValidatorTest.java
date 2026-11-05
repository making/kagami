package am.ik.kagami.token.web;

import am.ik.kagami.token.KagamiJwtClaims;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTokenValidatorTest {

	private static Jwt tokenScopedTo(String... repositories) {
		return Jwt.withTokenValue("token")
			.header("alg", "none")
			.claim("sub", "alice")
			.claim(KagamiJwtClaims.REPOSITORIES, List.of(repositories))
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
	}

	private static boolean rejected(String requestUri, Jwt token) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
		return new RepositoryTokenValidator(request).validate(token).hasErrors();
	}

	@Test
	void repositoryScopeIsCheckedAgainstTheDecodedPath() {
		Jwt token = tokenScopedTo("repoA");
		// control: the plain spelling is authorized
		assertThat(rejected("/artifacts/repoA/com/example/app-1.0.jar", token)).isFalse();
		// encoded prefix must not skip the repository claim check
		assertThat(rejected("/%61rtifacts/repoB/com/example/app-1.0.jar", token)).isTrue();
	}

}
