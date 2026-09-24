package am.ik.kagami.rbac;

import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.rbac.RbacBuiltins;
import am.ik.kagami.KagamiProperties.AuthenticationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RbacService}: group expansion, mappings for all property
 * namespaces, default-group fallback, empty groups and startup validation.
 */
class RbacServiceTest {

	private static KagamiProperties properties(KagamiProperties.Rbac rbac) {
		return KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp/kagami").build())
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(AuthenticationType.SIMPLE, List.of()))
			.rbac(rbac)
			.build();
	}

	private static Set<String> authorityNames(Set<org.springframework.security.core.GrantedAuthority> authorities) {
		return authorities.stream()
			.map(org.springframework.security.core.GrantedAuthority::getAuthority)
			.collect(java.util.stream.Collectors.toSet());
	}

	private static KagamiProperties.Mappings mappings(Map<String, List<String>> user,
			Map<String, List<String>> groups) {
		return new KagamiProperties.Mappings(user, groups);
	}

	@Test
	void unmappedUserFallsIntoDefaultGroup() {
		RbacService service = new RbacService(properties(KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.DEFAULT_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of(), Map.of()))
			.groupsClaim("groups")
			.build()));
		assertThat(authorityNames(service.authoritiesFor("nobody", List.of())))
			.containsExactlyInAnyOrder("artifacts:read", "artifacts:delete");
	}

	@Test
	void multipleGroupsExpandIntoTheUnionOfAuthorities() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.ADMINISTRATORS_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of("demo", List.of(RbacBuiltins.ADMINISTRATORS_GROUP, RbacBuiltins.VIEWERS_GROUP)),
					Map.of()))
			.groupsClaim("groups")
			.build();
		RbacService service = new RbacService(properties(rbac));
		assertThat(authorityNames(service.authoritiesFor("demo", List.of())))
			.containsExactlyInAnyOrder("artifacts:read", "artifacts:delete", "artifacts:admin");
	}

	@Test
	void emptyGroupGrantsNoAuthority() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.ADMINISTRATORS_GROUP)
			.groups(Map.of("no-access", List.of()))
			.mappings(mappings(Map.of("demo", List.of("no-access")), Map.of()))
			.groupsClaim("groups")
			.build();
		RbacService service = new RbacService(properties(rbac));
		assertThat(service.authoritiesFor("demo", List.of())).isEmpty();
	}

	@Test
	void configurableDefaultGroupAppliesToUnmappedUsers() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup("no-access")
			.groups(Map.of("no-access", List.of()))
			.mappings(mappings(Map.of(), Map.of()))
			.groupsClaim("groups")
			.build();
		RbacService service = new RbacService(properties(rbac));
		assertThat(service.authoritiesFor("nobody", List.of())).isEmpty();
	}

	@Test
	void idpGroupsClaimIsTranslatedThroughIdpGroupsMapping() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.ADMINISTRATORS_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of("user@example.com", List.of("viewers")),
					Map.of("my-team-admins", List.of("administrators"))))
			.groupsClaim("groups")
			.build();
		RbacService service = new RbacService(properties(rbac));
		assertThat(authorityNames(service.authoritiesFor("user@example.com", List.of("my-team-admins", "unknown"))))
			.containsExactlyInAnyOrder("artifacts:read", "artifacts:delete", "artifacts:admin");
	}

	@Test
	void validationRejectsUndefinedGroupInUserMapping() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.ADMINISTRATORS_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of("demo", List.of("ghost")), Map.of()))
			.groupsClaim("groups")
			.build();
		assertThatThrownBy(() -> new RbacService(properties(rbac)).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ghost")
			.hasMessageContaining("kagami.rbac.mappings.users.demo");
	}

	@Test
	void validationRejectsUndefinedGroupInIdpGroupsMapping() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.ADMINISTRATORS_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of(), Map.of("team", List.of("ghost"))))
			.groupsClaim("groups")
			.build();
		assertThatThrownBy(() -> new RbacService(properties(rbac)).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ghost");
	}

	@Test
	void validationRejectsUndefinedDefaultGroup() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup("ghost")
			.groups(Map.of())
			.mappings(mappings(Map.of(), Map.of()))
			.groupsClaim("groups")
			.build();
		assertThatThrownBy(() -> new RbacService(properties(rbac)).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ghost")
			.hasMessageContaining("kagami.rbac.default-group");
	}

	@Test
	void validationRejectsUnknownAuthorityInGroupDefinition() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.ADMINISTRATORS_GROUP)
			.groups(Map.of("hackers", List.of("repo:write")))
			.mappings(mappings(Map.of(), Map.of()))
			.groupsClaim("groups")
			.build();
		assertThatThrownBy(() -> new RbacService(properties(rbac)).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("repo:write");
	}

	@Test
	void validationAcceptsBuiltInConfiguration() {
		KagamiProperties.Rbac rbac = KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.DEFAULT_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of(), Map.of()))
			.groupsClaim("groups")
			.build();
		new RbacService(properties(rbac)).afterPropertiesSet();
	}

	@Test
	void issuableScopesComeFromPrincipalAuthorities() {
		RbacService service = new RbacService(properties(KagamiProperties.Rbac.builder()
			.defaultGroup(RbacBuiltins.DEFAULT_GROUP)
			.groups(Map.of())
			.mappings(mappings(Map.of(), Map.of()))
			.groupsClaim("groups")
			.build()));
		var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
			.authenticated("demo", null,
					List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("artifacts:read")));
		assertThat(service.issuableScopes(authentication)).containsExactly("artifacts:read");
	}

}
