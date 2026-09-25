# CLAUDE.md

Kagami is a mirror server of Maven repositories. Main package is `am.ik.kagami`.

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
./mvnw spring-boot:run                                          # Start backend server (port 8080)
./mvnw spring-boot:build-image                                  # Create Docker image
```

## Code Standards

General Java, Spring, package structure, and testing standards are defined in the skills
(`java-code-standards`, `spring-code-standards`, `java-package-structure`,
`java-testing-standards`). Consult and follow them when writing or reviewing code.

## Architecture Constraints

- Storage backend details are documented in the class javadocs: `StorageService` (single path to
  stored artifacts), `StorageConfig` (backend selection from `kagami.storage.type`),
  `StorageEnvironmentPostProcessor` (storage-type-derived defaults), `ArtifactContentType` (the
  one content type table), `RemoteRepositoryService` (scratch local repository per fetch).

## Testing Strategy

- **Contract Tests**: `StorageServiceContractTest` is the abstract contract every `StorageService`
  backend test must extend
- **E2E Tests**: `BrowserE2ETestBase` drives the built UI with Playwright (Chromium); each backend
  has a subclass, as do `KagamiIntegrationTestBase` and `BrowserControllerTestBase`
