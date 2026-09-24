package am.ik.kagami.rbac;

import java.util.List;
import java.util.Map;

/**
 * The RBAC vocabulary: the authority constants shared by JWT scopes and groups, and the
 * built-in group names. Authorities and scopes share one namespace, so authorization
 * rules and group definitions reference the same constants.
 */
public final class RbacBuiltins {

	/** The authority to download and view artifacts. */
	public static final String READ_AUTHORITY = "artifacts:read";

	/** The authority to remove artifacts. */
	public static final String DELETE_AUTHORITY = "artifacts:delete";

	/** The authority to run repository cache maintenance operations. */
	public static final String ADMIN_AUTHORITY = "artifacts:admin";

	/** The authority vocabulary available to group definitions. */
	public static final List<String> ALLOWED_AUTHORITIES = List.of(READ_AUTHORITY, DELETE_AUTHORITY, ADMIN_AUTHORITY);

	/** The built-in group granting every authority. */
	public static final String ADMINISTRATORS_GROUP = "administrators";

	/** The built-in group granting read and delete authorities. */
	public static final String EDITORS_GROUP = "editors";

	/** The built-in group granting read-only authorities. */
	public static final String VIEWERS_GROUP = "viewers";

	/** The group applied when no user or IdP group mapping matches. */
	public static final String DEFAULT_GROUP = EDITORS_GROUP;

	/** The default name of the OIDC claim that carries the IdP group memberships. */
	public static final String DEFAULT_GROUPS_CLAIM = "groups";

	/** The groups that exist even when nothing is configured. */
	public static final Map<String, List<String>> BUILT_IN_GROUPS = Map.of(ADMINISTRATORS_GROUP,
			List.of(READ_AUTHORITY, DELETE_AUTHORITY, ADMIN_AUTHORITY), EDITORS_GROUP,
			List.of(READ_AUTHORITY, DELETE_AUTHORITY), VIEWERS_GROUP, List.of(READ_AUTHORITY));

	private RbacBuiltins() {
	}

}
