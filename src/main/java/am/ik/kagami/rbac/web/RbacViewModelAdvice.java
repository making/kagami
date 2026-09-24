package am.ik.kagami.rbac.web;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.rbac.RbacBuiltins;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Supplies shared view flags to the server-rendered pages.
 */
@ControllerAdvice
public class RbacViewModelAdvice {

	private final KagamiProperties properties;

	public RbacViewModelAdvice(KagamiProperties properties) {
		this.properties = properties;
	}

	/**
	 * The current principal name used by the common site header.
	 * @param authentication the current authentication, or {@code null} for anonymous
	 * requests
	 * @return the principal name, or an empty string for anonymous requests
	 */
	@ModelAttribute("userName")
	public String userName(@Nullable Authentication authentication) {
		return authentication == null ? "" : authentication.getName();
	}

	/**
	 * Whether the current principal can access the administration UI.
	 * @param authentication the current authentication, or {@code null} for anonymous
	 * requests
	 * @return {@code true} when the principal holds the admin authority or scope
	 */
	@ModelAttribute("canAdmin")
	public boolean canAdmin(@Nullable Authentication authentication) {
		if (authentication == null) {
			return false;
		}
		String adminScope = "SCOPE_" + RbacBuiltins.ADMIN_AUTHORITY;
		return authentication.getAuthorities()
			.stream()
			.anyMatch(authority -> RbacBuiltins.ADMIN_AUTHORITY.equals(authority.getAuthority())
					|| adminScope.equals(authority.getAuthority()));
	}

	/**
	 * Whether the built-in development key pair is configured.
	 * @return {@code true} when the shared warning should be rendered
	 */
	@ModelAttribute("defaultJwtKey")
	public boolean defaultJwtKey() {
		return this.properties.jwt().defaultKeys();
	}

}
