# 008 Verify npm tarball integrity on download

Difficulty: Low

## Context

The npm mirror fetches a tarball from the packument's `dist.tarball` and stores it without
checking the content against `dist.integrity` / `dist.shasum`. The PyPI handler already verifies
`#sha256=` while streaming (`RemoteRepositoryService.fetchLinkedArtifact`); npm is the outlier.

A corrupted or tampered upstream body is therefore stored permanently and later served with a
content hash the client rejects.

## Approach

- Extend the npm tarball download path with the same streaming verification as the PyPI one.
- Parse `dist.integrity` (Subresource Integrity, `sha512-` by default, other algorithms when
  present) and fall back to `dist.shasum` (SHA-1) when `integrity` is absent.
- On mismatch, delete the stored object and return 502, as PyPI does; store nothing.
- Reuse the verification helper rather than duplicating the digest loop.

## Completion criteria

- A tarball whose bytes do not match `dist.integrity` is not stored and returns 502.
- A matching tarball is stored and served, and the second request performs no upstream call.
- The integrity value reported in the packument is unchanged.
