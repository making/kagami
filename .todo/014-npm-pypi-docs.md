# 007 Document the npm / PyPI mirrors

Difficulty: Low

## Context

README and `docs/api.md` describe a Maven mirror only. Users cannot discover the registry URLs, authentication, read-only behavior or cache freshness.

## Approach

- Add npm / PyPI quick starts to the README (`kagami.repositories.<id>.format`, minimal `.npmrc` and `pip.conf`, authentication).
- Extend `docs/api.md` with per-format endpoints, authentication (npm Bearer, pip Basic), response rewriting rules and cache freshness (mutable index TTL, permanent artifact caching).
- State the limitations: read-only (publish / delete returns 405), upstream host allowlists, and Maven-only features (sigstore) being disabled for npm/PyPI.
- Add `kagami.metadata.*` and the upstream allowlist hosts to the configuration reference.

## Completion criteria

- Following the README alone is enough to fetch through the mirror from npm / pip.
- URLs in the documentation match the actual routes.
- The existing Maven documentation is preserved.
