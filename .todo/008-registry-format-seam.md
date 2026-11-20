# 001 Repository format abstraction and mirror dispatch

Difficulty: High

## Context

The mirror request path is Maven-only in its current shape:

- `KagamiProperties.Repository` has no format, only a `url`
- `ArtifactController` calls `RemoteRepositoryService`, a Maven Resolver implementation, directly
- The pipeline assumes "request path = storage key = returned bytes", leaving no seam for response transformation

Adding npm / PyPI requires a seam for formats without breaking the existing behavior.

## Approach

- Add `kagami.repositories.<id>.format` (`maven` / `npm` / `pypi`, default `maven`). The default keeps existing configuration and Maven behavior unchanged.
- Keep `/artifacts/{repositoryId}/**` as the single mirror namespace for every format. This keeps the repository-bound token claim in `RepositoryTokenValidator`, the private/public rules in `SecurityConfig`, GC, statistics and browsing format-agnostic.
- Turn `ArtifactController` into a router that delegates to a per-format `MirrorHandler` (input: repository configuration, path, request metadata; output: status, headers, body). The Maven handler is a port of the current implementation and must not change behavior.
- Split packages into `am.ik.kagami.registry` (contract and router) and `am.ik.kagami.registry.maven|npm|pypi` (implementations). Under `PackageCycleArchTest`, `registry` may depend on `storage` and `KagamiProperties`, and implementations depend on the `registry` contract only.
- Validate format-specific configuration at startup (for example, reject `sigstore.enabled=true` on an npm repository, and reject unknown formats).
- Allow encoded slashes. This needs both Tomcat `server.tomcat.encoded-solidus-handling-policy=pass-through` and Spring Security `StrictHttpFirewall.setAllowUrlEncodedSlash(true)`. It is required for npm scoped packages (`/@scope%2Fname`).
- Every handler must pass paths through the `ArtifactLocation` validation (`..`, `~`, absolute paths) as the common defense.

## Completion criteria

- Existing tests pass unchanged (Maven responses, storage layout and headers are identical).
- The router dispatches to the npm / PyPI handler for repositories with the matching format (stubs are acceptable).
- `GET /artifacts/{repo}/@scope%2Fname` returns neither 400 nor a 404 from the router and reaches the handler as `@scope/name`.
- An unknown format, or `sigstore.enabled=true` on an npm repository, fails at startup.
