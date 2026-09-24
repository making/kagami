package am.ik.kagami.rbac;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.kagami.KagamiProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Expands group membership into authorities. A user belongs to the groups listed in the
 * {@code kagami.rbac.mappings.user.*} mapping and, for OIDC authentication, to the Kagami
 * groups translated from the IdP groups claim through
 * {@code kagami.rbac.mappings.groups.*}; the login authorities are the union of the
 * authorities of all these groups. Users absent from every mapping fall into
 * {@code kagami.rbac.default-group}.
 * <p>
 * Group names are not roles: they never appear in authorization rules, only the expanded
 * authorities do, which reuse the JWT scope vocabulary so that scope-based and
 * group-based authorization unify under {@code hasAuthority(...)}.
 */
@Component
public class RbacService implements InitializingBean {

	private final Logger logger = LoggerFactory.getLogger(RbacService.class);

	private final KagamiProperties properties;

	public RbacService(KagamiProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		validate(this.properties.rbac());
	}

	/**
	 * Fails fast when the RBAC configuration is inconsistent: every referenced group (in
	 * the mappings and in {@code default-group}) must be defined, and group definitions
	 * may only use the known authority vocabulary.
	 */
	void validate(KagamiProperties.Rbac rbac) {
		rbac.groups().forEach((group, authorities) -> {
			for (String authority : authorities) {
				if (!RbacBuiltins.ALLOWED_AUTHORITIES.contains(authority)) {
					throw new IllegalStateException("Group '%s' declares unknown authority '%s'. Allowed: %s"
						.formatted(group, authority, RbacBuiltins.ALLOWED_AUTHORITIES));
				}
			}
		});
		rbac.mappings().user().forEach((user, groups) -> {
			for (String group : groups) {
				requireDefinedGroup(rbac, group, "kagami.rbac.mappings.user.%s".formatted(user));
			}
		});
		rbac.mappings().groups().forEach((idpGroup, groups) -> {
			for (String group : groups) {
				requireDefinedGroup(rbac, group, "kagami.rbac.mappings.groups.%s".formatted(idpGroup));
			}
		});
		requireDefinedGroup(rbac, rbac.defaultGroup(), "kagami.rbac.default-group");
	}

	private static void requireDefinedGroup(KagamiProperties.Rbac rbac, String group, String source) {
		if (!rbac.groups().containsKey(group)) {
			throw new IllegalStateException(
					"Group '%s' referenced by %s is not defined under kagami.rbac.groups.*".formatted(group, source));
		}
	}

	/**
	 * The authorities of the given user: the union of the authorities of all groups the
	 * user belongs to, or the authorities of the {@code default-group} when the user is
	 * absent from every mapping.
	 * @param userName the user name (the simple auth username or the OIDC user name)
	 * @param idpGroups the groups claim of the OIDC provider, empty for simple
	 * authentication; IdP group names without a {@code kagami.rbac.mappings.groups.*}
	 * mapping are ignored
	 */
	public Set<GrantedAuthority> authoritiesFor(String userName, Collection<String> idpGroups) {
		KagamiProperties.Rbac rbac = this.properties.rbac();
		Set<String> groupNames = new LinkedHashSet<>(rbac.mappings().user().getOrDefault(userName, List.of()));
		for (String idpGroup : idpGroups) {
			List<String> groups = rbac.mappings().groups().get(idpGroup);
			if (groups == null) {
				this.logger.debug("IdP group {} is not mapped to any Kagami group", idpGroup);
			}
			else {
				groupNames.addAll(groups);
			}
		}
		if (groupNames.isEmpty()) {
			groupNames.add(rbac.defaultGroup());
		}
		Set<GrantedAuthority> authorities = new LinkedHashSet<>();
		for (String groupName : groupNames) {
			this.logger.info("User {} belongs to group {}", userName, groupName);
			for (String authority : rbac.groups().getOrDefault(groupName, List.of())) {
				authorities.add(new SimpleGrantedAuthority(authority));
			}
		}
		return authorities;
	}

	/**
	 * The JWT scopes the given principal may issue tokens with. For a JWT-authenticated
	 * principal this is the scope claim of the presented token; for a web principal it is
	 * the subset of the authority vocabulary the principal holds, so a user whose groups
	 * grant no {@code artifacts:delete} authority cannot issue a delete-scoped token.
	 */
	public Set<String> issuableScopes(Authentication authentication) {
		if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
			return jwtAuthenticationToken.getAuthorities()
				.stream()
				.map(GrantedAuthority::getAuthority)
				.filter(authority -> authority.startsWith("SCOPE_"))
				.map(authority -> authority.substring("SCOPE_".length()))
				.filter(RbacBuiltins.ALLOWED_AUTHORITIES::contains)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		}
		return authentication.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(RbacBuiltins.ALLOWED_AUTHORITIES::contains)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

}
