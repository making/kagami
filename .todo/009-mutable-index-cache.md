# 002 Cache and revalidation of mutable indexes

Difficulty: High

## Context

The current cache returns any stored file unconditionally, which assumes immutable Maven artifacts. npm packuments and PyPI simple indexes are mutable and contain absolute upstream URLs.

- A packument or index fetched once stays stale forever. The existing `maven-metadata.xml` handling has the same defect.
- `ArtifactLocation` / `StorageService` only model an artifact path space. Storing indexes at their natural URL paths collides:
  - npm: `@scope/name` (metadata) versus `@scope/name/-/x.tgz` (a directory)
  - PyPI: `simple/foo/` (index) versus files below it
- `S3StorageService.store` derives the Content-Type from the file extension, so it cannot preserve the Content-Type of an index document.

## Approach

- Add a separate **metadata cache layer** next to `StorageService` and keep index documents out of the artifact storage. This avoids polluting the storage namespace and the browse listing.
- Implement it as an in-memory, single-flight, bounded LRU keyed by "repository + upstream URL", not by request path.
- Let each format handler declare its revalidation policy: immutable artifacts, or mutable indexes with a TTL plus conditional refetch using `ETag` / `Last-Modified`.
- Reuse the stored body on a 304. Serve stale content for a bounded period when the upstream fails (5xx or timeout).
- Send `Cache-Control: no-cache` (or a short `max-age`) for mutable indexes and keep the one-year cache for artifacts.
- Treat Maven `maven-metadata.xml` as mutable and route it through the same policy.
- Document that replicas do not share the cache: every instance revalidates after its TTL. Persistent caching is a separate item if it becomes necessary.
- Bound the size of an upstream index body so a pathological upstream cannot exhaust the heap.

## Completion criteria

- A request after the TTL triggers a conditional refetch against the upstream.
- A 304 response returns the stored body without downloading it again.
- An upstream 5xx returns stale content, and after the bound is exceeded returns 502.
- Concurrent requests for the same key result in exactly one upstream call (count calls with MockServer).
- Metadata cache entries do not appear in the `/browse` listing.
