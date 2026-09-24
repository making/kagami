# Group-based RBAC for USER principals

Difficulty: High

## Goal

Add group-based RBAC on top of the existing API-key-scope authorization, for both simple
(static username) and OIDC authentication. A group is a named set of authorities; users are
mapped to groups via properties. Group names are NOT roles: a group expands into the
authorities it declares, which reuse the JWT scope vocabulary (`artifacts:read`,
`artifacts:delete`), so scope-based and group-based authorization unify under
`hasAuthority(...)`.

## Current state (verified)

- `SecurityConfig` grants `ROLE_USER` two ways: simple auth via `spring.security.user.roles=USER`;
  OIDC via a `GrantedAuthoritiesMapper` that matches `kagami.authentication.allowed-name-patterns`
  against the IdP user name.
- Authorization rules: private repo GET/HEAD = scope `artifacts:read` OR `ROLE_USER`; DELETE =
  scope `artifacts:delete` OR `ROLE_USER`; everything else (`/token`, UI) = `ROLE_USER` only.
- JWT tokens carry a `repositories` claim checked by `RepositoryTokenValidator`; scopes are
  chosen at issuance time. Token generation currently requires only `ROLE_USER`.

## Design

### Properties (new `Rbac` record in `KagamiProperties`, prefix `kagami.rbac.*`)

```properties
# group definitions: group name -> authorities (same vocabulary as JWT scopes)
kagami.rbac.groups.administrators=artifacts:read,artifacts:delete
kagami.rbac.groups.editors=artifacts:read,artifacts:delete
kagami.rbac.groups.viewers=artifacts:read

# username -> groups (common to simple and OIDC auth); keys containing @ or .
# need the bracket notation or Spring Boot relaxed binding mangles them
kagami.rbac.users.demo=administrators
kagami.rbac.users[taro@example.com]=editors

# OIDC groups claim (IdP group names) -> Kagami groups
kagami.rbac.idp-groups.my-team-admins=administrators

# group applied to users absent from every mapping (default: administrators)
kagami.rbac.default-group=no-access
```

Structure:

- `Rbac(@DefaultValue("administrators") String defaultGroup,
  @DefaultValue Map<String, List<String>> groups,
  @DefaultValue Map<String, List<String>> users,
  @DefaultValue Map<String, List<String>> idpGroups)` — the maps default empty.
- The three built-in groups above exist as defaults; `kagami.rbac.groups.*` overrides or adds
  entries. Empty values are allowed: `kagami.rbac.groups.no-access=` defines a group with no
  authorities (e.g. a "can log in but do nothing" holding group, or a restricted
  `default-group`). Startup validation: every referenced group (in mappings and in
  `default-group`) must be defined, fail-fast otherwise.
- Backward compatibility: the default `default-group=administrators` (all authorities) means an
  unconfigured deployment behaves exactly like today. Pointing `default-group` at an empty
  group yields the "only explicitly mapped users have authority" style.

### Authorization model

- Login grants the union of authorities of all groups the user belongs to
  (`GrantedAuthoritiesMapper` for both auth types; simple auth matches `users` by username,
  OIDC matches `users` by user name plus the IdP groups claim translated through `idp-groups`).
  Users absent from every mapping fall into `default-group`.
- `SecurityConfig` rules switch from `hasRole(...)` to `hasAuthority(...)`:
  private repo GET/HEAD = `hasAuthority("artifacts:read")`; DELETE =
  `hasAuthority("artifacts:delete")`. Group membership and JWT scope therefore satisfy the
  same rules through one authority namespace.
- UI / `/token`: accessible to any authenticated web user (i.e. everyone, since `default-group`
  always applies). Token issuance scope cap = the authorities the principal holds (viewers
  cannot issue `artifacts:delete` tokens). `repositories` claim restriction stays as-is.
- `allowedNamePatterns` stays the OIDC admission gate and must remain a HARD gate: with
  `anyRequest().authenticated()` a non-matching OIDC user would otherwise pass as
  "authenticated with zero authorities" and even receive `default-group` authorities, silently
  voiding the gate. Therefore the OIDC `GrantedAuthoritiesMapper` throws (login failure) for
  non-matching users instead of just returning empty authorities, and logs the rejection. The
  layers stay orthogonal: patterns decide WHO may log in (authentication admission), RBAC
  decides WHAT admitted users can do. Rejected alternative: treating pattern-non-match as
  "empty group membership" — collides with `default-group` applying to everyone. Simple auth
  has a single static user and is out of scope for patterns (unchanged).

### Implementation

1. `Rbac` records in `KagamiProperties` with built-in group defaults + startup validation.
   Include a binding test that an empty value (`kagami.rbac.groups.no-access=`) binds to an
   empty authority list rather than failing or becoming a one-element list with `""`.
2. `GrantedAuthoritiesMapper` implementation (new class in a `rbac` feature package, used by
   `SecurityConfig` for both simple and OIDC paths; simple path needs the mapper applied to the
   form-login/remember-me authentication, e.g. via a `UserDetails`-level authority calculation
   or an `AuthenticationProvider` wrapper). Include a binding test that proves bracket-notation
   keys (e.g. `kagami.rbac.users[taro@example.com]`) survive relaxed binding intact.
3. Rewrite `SecurityConfig` rules with `hasAuthority`; replace `anyRequest().hasRole("USER")`
   with `anyRequest().authenticated()`. Rationale: the old ROLE_USER gate only meant "logged in
   via form/OIDC"; real power lives in the `artifacts:*` rules and the token-scope cap. Keeping
   an authority check here would lock out empty-authority users (e.g. a holding `default-group`)
   right after login, contradicting the empty-group design. Empty-authority users can browse the
   UI but cannot issue any token (existing "select at least one scope" validation) and see no
   privileged actions.
4. Scope capping in `TokenController` / `TokenPageController` from the principal's authorities;
   token form hides checkboxes above the cap; delete actions hidden for read-only users.
5. Tests: group expansion (multiple groups = union), mappings for all property namespaces,
   `default-group` fallback, empty groups, private repo read/delete matrix, token scope
   capping, undefined-group fail-fast, unconfigured-deployment compatibility. E2E via
   `BrowserE2ETestBase` subclasses for both auth types.

## Notes

- Deliberately no `ROLE_*`: group names never appear in authorization rules, only their
  expanded authorities do.
- Authorities unknown to Kagami (not `artifacts:*`) in a group definition should be rejected at
  startup validation for now (vocabulary = JWT scopes).
