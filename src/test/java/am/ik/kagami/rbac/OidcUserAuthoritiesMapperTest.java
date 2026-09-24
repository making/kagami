package am.ik.kagami.rbac;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.rbac.RbacBuiltins;
import am.ik.kagami.KagamiProperties.AuthenticationType;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OidcUserAuthoritiesMapper}: the allowed name patterns stay a hard
 * admission gate and admitted users are expanded through the RBAC groups.
 */
class OidcUserAuthoritiesMapperTest {

	private static OidcUserAuthority oidcUser(String email,
			java.util.@org.jspecify.annotations.Nullable List<String> groups) {
		Map<String, Object> claims = new java.util.HashMap<>();
		claims.put("iss", "https://idp.example.com");
		claims.put("sub", email);
		claims.put("email", email);
		if (groups != null) {
			claims.put("groups", groups);
		}
		OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(60), claims);
		OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", email));
		return new OidcUserAuthority(idToken, userInfo);
	}

	private static OidcUserAuthoritiesMapper mapper(KagamiProperties.Rbac rbac, List<Pattern> patterns) {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp/kagami").build())
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(AuthenticationType.OIDC, patterns))
			.rbac(rbac)
			.build();
		return new OidcUserAuthoritiesMapper(properties, new RbacService(properties));
	}

	@Test
	void admittedUserIsExpandedThroughRbacGroups() {
		OidcUserAuthoritiesMapper mapper = mapper(
				new KagamiProperties.Rbac(RbacBuiltins.ADMINISTRATORS_GROUP, Map.of(),
						new KagamiProperties.Mappings(Map.of("user@example.com", List.of("viewers")), Map.of())),
				List.of(Pattern.compile(".*@example.com")));
		List<String> authorities = mapper.mapAuthorities(List.of(oidcUser("user@example.com", null)))
			.stream()
			.map(org.springframework.security.core.GrantedAuthority::getAuthority)
			.toList();
		assertThat(authorities).containsExactly("artifacts:read");
	}

	@Test
	void idpGroupsClaimIsTranslated() {
		OidcUserAuthoritiesMapper mapper = mapper(
				new KagamiProperties.Rbac(RbacBuiltins.ADMINISTRATORS_GROUP, Map.of(),
						new KagamiProperties.Mappings(Map.of(), Map.of("my-team-admins", List.of("administrators")))),
				List.of(Pattern.compile(".*")));
		List<String> authorities = mapper
			.mapAuthorities(List.of(oidcUser("user@example.com", List.of("my-team-admins"))))
			.stream()
			.map(org.springframework.security.core.GrantedAuthority::getAuthority)
			.toList();
		assertThat(authorities).containsExactlyInAnyOrder("artifacts:read", "artifacts:delete");
	}

	@Test
	void nonMatchingUserIsRejectedWithLoginFailure() {
		OidcUserAuthoritiesMapper mapper = mapper(new KagamiProperties.Rbac(RbacBuiltins.ADMINISTRATORS_GROUP, Map.of(),
				new KagamiProperties.Mappings(Map.of(), Map.of())), List.of(Pattern.compile(".*@example.com")));
		// A rejected user must not pass as "authenticated with zero authorities": the
		// mapper throws so that the login itself fails and the default-group never
		// applies
		assertThatThrownBy(() -> mapper.mapAuthorities(List.of(oidcUser("intruder@example.net", null))))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void authoritiesWithoutOidcUserMapToNothing() {
		OidcUserAuthoritiesMapper mapper = mapper(new KagamiProperties.Rbac(RbacBuiltins.ADMINISTRATORS_GROUP, Map.of(),
				new KagamiProperties.Mappings(Map.of(), Map.of())), List.of(Pattern.compile(".*")));
		assertThat(mapper
			.mapAuthorities(List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_X"))))
			.isEmpty();
	}

}
