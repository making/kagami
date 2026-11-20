# 005 PyPI simple index mirror

Difficulty: High

## Context

pip / uv read file links from the simple index (PEP 503 HTML, PEP 691 JSON) and follow those URLs to download wheels and sdists. As with npm, links must be rewritten to the mirror or clients bypass it.

## Approach

- Use `/artifacts/{repositoryId}/simple/` as the index URL; repositories with `format=pypi` handle it.
- Endpoints:
  - `GET|HEAD /simple/` - the list of all projects
  - `GET|HEAD /simple/{project}/` - the project's file list, both HTML and PEP 691 JSON
  - `GET|HEAD /files/{project}/{filename}` - the rewritten file URL; fetched from the upstream and stored
- Resolve paths with the PEP 503 normalization (lowercase, collapse runs of `-_.` to `-`). Return the upstream `name` as the display name.
- Rewriting:
  - Replace `files[*].url` in PEP 691 JSON and `href` in PEP 503 HTML with `{baseUrl}/artifacts/{repositoryId}/files/{project}/{filename}`
  - Preserve the `#sha256=...` fragment; content does not change, so the hash does not either
  - Rewrite PEP 658 `.metadata` links and PEP 714 `.provenance` links with the same rule
  - Preserve attributes such as `data-requires-python` and `data-dist-info-metadata`
- Forward `Accept` to the upstream and return the upstream Content-Type as is. If the upstream lacks PEP 691, keep HTML and do not fabricate JSON.
- Follow the link URL for downloads, but validate the host against a configurable allowlist (`pypi.org`, `files.pythonhosted.org`, ...). If the link carries `sha256`, verify after download and return 502 without storing on mismatch.
- Reuse the existing Basic auth settings for the upstream. pip resends credentials after a 401, so confirm the existing `WWW-Authenticate: Basic` advertisement works.
- Preserve unknown PEP 691 fields such as `versions` and `meta`.
- Accept only project names within the PEP 503 normalized character set and return 400 otherwise.

## Completion criteria

- A MockServer upstream serving both HTML and JSON indexes produces rewritten links for every file, with hash fragments and PEP 658 links preserved.
- Fetching a rewritten URL downloads and stores the file, and the second request performs no upstream call.
- A file whose `sha256` does not match is not stored and returns 502.
- `Accept: application/vnd.pypi.simple.v1+json` returns JSON and the default returns HTML, following the upstream response.
- Normalized names (`Zope.Interface` and `zope-interface`) reach the same index.
