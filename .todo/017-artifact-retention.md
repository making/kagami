# 010 Artifact retention and cache eviction

Difficulty: High

## Context

Kagami caches artifacts forever: a stored tarball, wheel or jar is served until someone deletes
it from the browse page. That is fine for a Maven proxy on a large disk, but a mirror of npm and
PyPI traffic grows without bound, and the garbage collector only removes empty or
bookkeeping-only directories (todos 016), never artifacts.

Decide and implement a retention policy that can be enabled per repository, without breaking the
immutability assumptions the request path relies on.

## Approach

- Configuration: `kagami.repositories.<id>.retention.max-size` and `retention.max-age`, both
  optional, unset meaning "keep forever" (today's behaviour).
- Eviction unit: an artifact together with its sidecars (checksums, sigstore bundles), never a
  partial set. npm: `<package>/-/<file>.tgz`; PyPI: `files/<project>/<filename>`; Maven: the
  version directory.
- Order: oldest `lastModified` first (LRU by access is a separate decision; document the choice).
- Trigger: a scheduled task plus, optionally, after a store that pushes the repository over the
  limit. Never evict the object that is being served right now.
- Metadata cache is separate and already bounded (todo 009); do not touch it here.
- Expose the evicted count and current repository size as metrics, and surface the policy in the
  admin page and the configuration reference.

## Completion criteria

- With `max-age` set, an artifact older than the limit is evicted and a later request refetches
  it from the upstream.
- With `max-size` set, the repository stays under the limit after eviction.
- Sidecars travel with their artifact; no checksum or bundle outlives its artifact or is left
  behind.
- An unset policy changes nothing (existing tests pass unchanged).
- Documentation states the trigger, the eviction order and the fact that replicas do not share
  the cache.
