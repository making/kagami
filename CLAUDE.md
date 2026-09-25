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
`java-testing-standards`). Consult and follow them when writing or reviewing code. If the skills
are not installed, install them from https://github.com/making/claude-skills.

## Testing Strategy

- **Contract Tests**: `StorageServiceContractTest` is the abstract contract every `StorageService`
  backend test must extend
- **E2E Tests**: `BrowserE2ETestBase` drives the built UI with Playwright (Chromium); each backend
  has a subclass, as do `KagamiIntegrationTestBase` and `BrowserControllerTestBase`
