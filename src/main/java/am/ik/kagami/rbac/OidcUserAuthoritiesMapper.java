package am.ik.kagami.rbac;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import am.ik.kagami.KagamiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

/**
 * The {@link GrantedAuthoritiesMapper} for the OIDC login path. It stays the admission
 * gate ({@code kagami.authentication.allowed-name-patterns}: a user whose name does not
 * match any pattern is rejected with a login failure) and then expands the RBAC group
 * membership: the {@code kagami.rbac.mappings.users.*} mapping matched by user name plus
 * the Kagami groups translated from the IdP groups claim. The two layers are orthogonal:
 * the patterns decide WHO may log in, RBAC decides WHAT admitted users can do.
 */
public class OidcUserAuthoritiesMapper implements GrantedAuthoritiesMapper {

	private final Logger logger = LoggerFactory.getLogger(OidcUserAuthoritiesMapper.class);

	private final KagamiProperties properties;

	private final RbacService rbacService;

	public OidcUserAuthoritiesMapper(KagamiProperties properties, RbacService rbacService) {
		this.properties = properties;
		this.rbacService = rbacService;
	}

	@Override
	public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
		for (GrantedAuthority grantedAuthority : authorities) {
			if (grantedAuthority instanceof OidcUserAuthority oidcUserAuthority) {
				return mapOidcUser(oidcUserAuthority);
			}
		}
		return List.of();
	}

	private List<? extends GrantedAuthority> mapOidcUser(OidcUserAuthority oidcUserAuthority) {
		String userName = (String) oidcUserAuthority.getAttributes().get(oidcUserAuthority.getUserNameAttributeName());
		if (userName == null || !isAllowedName(userName)) {
			this.logger.info("User {} does not match any allowed name patterns, rejecting login", userName);
			throw new AccessDeniedException("User '%s' is not allowed to log in".formatted(userName));
		}
		this.logger.info("Allowed name pattern: {} => allowed", userName);
		return new ArrayList<>(this.rbacService.authoritiesFor(userName, idpGroups(oidcUserAuthority)));
	}

	private boolean isAllowedName(String userName) {
		return this.properties.authentication()
			.allowedNamePatterns()
			.stream()
			.anyMatch(pattern -> pattern.matcher(userName).matches());
	}

	@SuppressWarnings("unchecked")
	private static List<String> idpGroups(OidcUserAuthority oidcUserAuthority) {
		Object groups = oidcUserAuthority.getAttributes().get("groups");
		return groups instanceof Collection<?> collection
				? (List<String>) collection.stream().map(String::valueOf).toList() : List.of();
	}

}
