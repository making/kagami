# 003 Upstream streaming and download responses

Difficulty: Medium

## Context

npm tarballs and PyPI wheels can reach hundreds of megabytes, but the current upstream fetch reads whole bodies.

- `RemoteRepositoryService.fetchNonStandardFile` and the sigstore bundle fetch buffer the entire response in memory.
- `ArtifactController` derives the Content-Type from the extension and ignores the upstream value.
- HEAD, Range and conditional GET are not handled explicitly. uv uses Range for resumable downloads.
- Forwarding the client's `Accept-Encoding` to the upstream can store a compressed body as if it were uncompressed.

## Approach

- Stream upstream responses into `StorageService.store`; never buffer the whole body.
- Prefer the upstream response Content-Type and fall back to the existing extension table only when it is absent. Add `tgz`, `tar.gz`, `whl` and `zip` to `ArtifactContentType`.
- Request `Accept-Encoding: identity` from the upstream. If a compressed response still arrives, decompress it before storing.
- Serve HEAD through the same path as GET without a body, with `Content-Length` and `Accept-Ranges: bytes`.
- Implement single-range 206 responses for stored artifacts; fall back to 200 for multi-range requests.
- Implement conditional GET (`If-None-Match` / `If-Modified-Since`) returning 304.
- Keep `Content-Disposition`, but build the filename from the storage key rather than an upstream header.

## Completion criteria

- Fetching a large tarball / wheel does not grow peak heap in proportion to the body size (cover the streaming path with a test).
- `Range: bytes=0-99` returns 206 with `Content-Range`.
- HEAD returns an empty body and the correct `Content-Length`.
- A `Content-Encoding: gzip` upstream response is stored and served as the correctly decompressed body.
- Existing Maven response headers are unchanged.
