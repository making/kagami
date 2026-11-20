# 004 npm registry mirror

Difficulty: High

## Context

The npm client fetches registry metadata and then follows `dist.tarball` to download the tarball. If the mirror returns the upstream metadata verbatim, clients fetch tarballs directly from the upstream and the mirror is bypassed.

## Approach

- Use `/artifacts/{repositoryId}/` as the registry URL; repositories with `format=npm` handle it.
- Endpoints and storage:
  - `GET|HEAD /{package}` (packument) - metadata cache (item 009)
  - `GET|HEAD /{package}/{version}` - a single version's metadata
  - `GET|HEAD /@scope%2F{name}` and `/@scope/{name}` - normalize both scoped forms to the same package
  - `GET|HEAD /{package}/-/{file}` - tarball; store it and serve the stored copy afterwards
- Packument rewriting:
  - Replace `versions[*].dist.tarball` with `{baseUrl}/artifacts/{repositoryId}/{packagePath}/-/{filename}`
  - Leave `dist.shasum` / `dist.integrity` unchanged because they are content hashes
  - Apply the same rewrite to `Accept: application/vnd.npm.install-v1+json` (corgi) responses
- Build `{baseUrl}` from the configured external base URL. Whether `X-Forwarded-*` is trusted follows the existing `server.forward-headers-strategy`.
- Fetch tarballs from the packument's `dist.tarball`, but validate the host against an allowlist (`upstream.tarball-hosts`). Store a fetched tarball only under the requested package so it cannot leak into another package.
- Upstream auth: add a Bearer `token` alongside the existing `username` / `password` (for Artifactory / GitHub Packages npm registries). Do not forward the client's `Authorization` header because it carries a Kagami JWT, not upstream credentials.
- Return 200 locally for `GET /-/ping`. Pass `GET /-/v1/search` and `POST /-/npm/v1/security/audits/quick` through without storing, and propagate a 404 from registries without an audit API.
- Return 405 for publish (`PUT` / `DELETE /{package}`) since the mirror is read-only.
- Accept only `name` or `@scope/name` package names and return 400 otherwise. Validate paths through `ArtifactLocation`.

## Completion criteria

- A MockServer upstream packument is served through the mirror with every `dist.tarball` pointing at the mirror.
- Fetching a rewritten URL downloads and stores the tarball from the upstream, and `integrity` matches the original value.
- Scoped packages resolve through both the `%2F` and `/` forms.
- The second tarball request performs zero upstream calls.
- An upstream 404 becomes 404 and an upstream 5xx becomes 502.
- A `dist.tarball` on a host outside the allowlist is not fetched and returns 502.
