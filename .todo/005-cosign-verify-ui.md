# cosign signature verification in the UI

Difficulty: High

Depends on: `.todo/003-sigstore-bundle-proxying.md`

## Goal

Run `cosign verify-blob-attestation` from the UI against an artifact in storage plus its sigstore bundle,
and display the verification result.

## Verified facts (cosign v2, round-trip tested locally on 2026-09-25)

- On success: `Verified OK` on stdout, process exit code 0.
- On digest mismatch: `Error: failed to verify signature: provided artifact digest does not match any
  digest in statement` on stderr. Capture the exit code directly via the Process API in Java — it is hard
  to read reliably through a pipeline.
- Skipping tlog verification emits a WARNING on stderr (when using `--insecure-ignore-tlog=true`). Keep
  stdout and stderr separate in the result display.
- Verification command:
  ```
  cosign verify-blob-attestation \
    --bundle <bundle.json> --key <public-key.pem> \
    --insecure-ignore-tlog=true --type slsaprovenance1 \
    --digest <sha256-hex> --digestAlg sha256
  ```
- Verified against the live environment: for commons-lang3 3.19.0 / 3.20.0 in `tanzu-spring-dependencies`,
  downloaded jar + `.attestation.sigstore.json` and got `Verified OK` with the build factory public key
  (`https://storage.googleapis.com/tanzu-signing-bucket/build-factory/public-key.pem`).
- Conversely, commons-lang3 3.14.0 has a stale bundle causing a digest mismatch. Verification failures
  happen in real operation, so the UI must clearly present the failure reason (stderr).
- The subject digest is readable from the in-toto statement inside the DSSE envelope, so a pre-check
  (comparing the computed digest against the subject digest) before invoking cosign is also possible.
- Maven Central (https://central.sonatype.org/news/20250128_sigstore_signature_validation_via_portal/):
  artifacts may carry a `<filename>.sigstore.json` sidecar (optional today; Portal validates uploads and
  invalid ones will eventually be rejected). Bundles are **keyless** (Fulcio certificate + Rekor tlog),
  produced by sigstore-java's sigstore-maven-plugin (GitHub Actions OIDC). Verified live: keyless
  `cosign verify-blob --bundle <x.jar.sigstore.json> --certificate-identity-regexp "https://github.com/<owner>/<repo>/.*"
  --certificate-oidc-issuer https://token.actions.githubusercontent.com <x.jar>` → `Verified OK`
  (no `--key`, no `--insecure-ignore-tlog`; tlog IS verified for Central bundles).
- ⇒ Two verification modes are needed:
  1. **Pinned-key mode** (e.g. Tanzu): `verify-blob-attestation --key <pinned pem> --insecure-ignore-tlog=true`
  2. **Keyless mode** (e.g. Maven Central): `verify-blob` with the bundle's embedded certificate,
     identity/issuer constraints, and full tlog verification.
  Make the mode part of the per-repository sigstore config (e.g. `sigstore.verification: key | keyless`,
  with keyless requiring identity/issuer regex settings).

## Design

- Two verification modes, configured per repository (see Verified facts):
  - `key`: pinned public key (`sigstore.public-key-url`, added in 003) → `verify-blob-attestation`
    with `--insecure-ignore-tlog=true`
  - `keyless`: no pinned key; verify the bundle's embedded Fulcio certificate with
    `verify-blob --certificate-identity-regexp ... --certificate-oidc-issuer ...` and full tlog
    verification. Identity/issuer regexes are part of the repository config.
- Do not accept user-supplied keys or identity patterns at verification time (they would enable
  arbitrary input into the command). All trust anchors come from repository config.
- New package `am.ik.kagami.sigstore` (package by feature):
  - `CosignVerifier`: retrieve the artifact from storage → compute sha256 digest → write bundle + key to
    temp files → run cosign via `ProcessBuilder` → return a result record (`verified`, `stdout`, `stderr`,
    `exitCode`).
  - The cosign binary path is a property (`kagami.sigstore.cosign-path`, default `cosign`). Set a run
    timeout.
  - Handle environments without cosign installed: disable the verify button or show an error message
    (decide at implementation time between a startup `--version` probe vs. runtime error handling).
- Endpoint: an htmx fragment POST under `BrowseController` (e.g. `POST /browse/{repositoryId}/**/verify-sigstore`).
  RBAC follows the existing browse permission.
- Add a "Verify with cosign" button to the `fragments/file-info` modal → htmx swaps in a result fragment
  (`Verified OK` / error detail with stdout/stderr).

## Tasks

- [ ] Add the `kagami.sigstore.cosign-path` property
- [ ] Implement `CosignVerifier` (digest computation, temp file management, timeout, exit-code decision)
- [ ] Implement public key fetch + cache (key mode, using 003's `sigstore.public-key-url`)
- [ ] Implement keyless mode (no `--key`; identity/issuer flags from repository config)
- [ ] Add the result fragment and the button to the file-info modal
- [ ] Unit tests: mock the process execution so tests do not depend on real cosign. Conditionally gate a
      real-cosign integration test with `@EnabledIf` etc., considering whether CI has cosign
- [ ] Add verification flow tests (both success and failure paths) to `BrowserControllerTestBase` / E2E

## Notes

- cosign is an external binary that must be installed in the Kagami runtime environment. Document this
  prerequisite in the README.
- Bundle verification assumes the artifact itself is in storage (handle the error when GC has removed it).
