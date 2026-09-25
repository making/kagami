# Transparent proxying of sigstore attestation bundles

Difficulty: Medium

## Goal

Make sigstore signature bundles distributed by upstream repositories alongside artifacts
(e.g. `<jar>.attestation.sigstore.json`) downloadable through Kagami.
Implement it as a generic "sidecar file of an artifact" mechanism, not Tanzu-specific.
(Maven Central also distributes `.sigstore.json` / `.sigstore`, so a suffix list covers those too.)

## Reference

- Tanzu "Secure Build Artifacts" documentation:
  https://techdocs.broadcom.com/us/en/vmware-tanzu/spring/tanzu-spring/commercial/spring-tanzu/secure-build-artifacts.html

## Verified facts (verified against the live repository on 2026-09-25)

- Upstream URL scheme: `https://tanzu-spring-dependencies.packages.broadcom.com/tanzu-spring-dependencies/<path>`
  (requires Basic auth; 401 without credentials). Sibling repositories `tanzu-maven` / `spring-enterprise`
  also exist on the same host.
- Bundle naming: `<artifact-filename>.attestation.sigstore.json`. Confirmed to exist
  (e.g. `org/apache/commons/commons-lang3/3.19.0/commons-lang3-3.19.0.jar.attestation.sigstore.json` → 200).
  However not every artifact has one (spring-boot itself and some versions do not), so treating 404 as
  normal (sidecar simply absent) is the right design.
- Build factory public key: `https://storage.googleapis.com/tanzu-signing-bucket/build-factory/public-key.pem`
  (200 OK, downloadable without auth).
- Maven Central publishes an optional `<filename>.sigstore.json` sidecar per artifact
  (https://central.sonatype.org/news/20250128_sigstore_signature_validation_via_portal/), keyless
  (Fulcio cert + Rekor), produced by sigstore-java's sigstore-maven-plugin. Confirmed live:
  `dev.sigstore/sigstore-maven-plugin/2.3.0` carries `*.pom.sigstore.json` (+ its own checksum files).
- Caveat: `commons-lang3-3.14.0` has a bundle whose attested digest does not match the actual sha256 of the
  jar in the repository (stale bundle). Verification fails for it. The UI should just display the failure
  as-is; no workaround needed on the Kagami side.

## Design

- Add sigstore settings to `KagamiProperties.Repository`:
  - `sigstore.enabled` (default false)
  - `sigstore.bundle-suffixes` (default: `attestation.sigstore.json`, `sigstore.json`) — sidecars tried as
    artifact filename + suffix against the upstream
  - `sigstore.public-key-url` (for verification, used by 005)
- In `RemoteRepositoryService.fetchArtifact()` (right before/after a successful artifact fetch), try each
  bundle suffix sidecar against the upstream; on 200, store it in `StorageService` via
  `ArtifactLocation.sibling()`. Ignore 404 (normal case: bundle absent).
- Sidecar requests must carry the upstream credentials (Basic auth) just like artifact requests.
- Sidecars should stream through `ArtifactController`'s normal path (no dedicated endpoint expected; if the
  file is in storage, the existing lookup hits it).
- Sidecar fetch failure must not break the artifact response itself (fail-open).

## Tasks

- [ ] Add sigstore settings to `KagamiProperties.Repository`
- [ ] Implement sidecar fetch + storage save in `RemoteRepositoryService`
- [ ] Apply upstream credentials (Basic auth) to sidecar requests
- [ ] Test that bundles download through `ArtifactController` (RepositoryControllerTestBase family)
- [ ] Test that a sidecar 404 does not affect the artifact response
- [ ] Add a config example to `application-tanzu.properties` (if needed)

## Notes

- Verifying actual bundle downloads in the live environment requires Tanzu entitlement credentials
  (not reproduced here); cover it in tests via MockServer serving the sidecar.
