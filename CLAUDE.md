# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

Kagami is a mirror server of Maven repositories.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
./mvnw spring-boot:run                                          # Start backend server (port 8080)
./mvnw spring-boot:build-image                                  # Create Docker image
```

**Docker:**
- Pre-built image: `ghcr.io/making/kagami:jvm`

## Architecture Constraints

- `StorageService` (`am.ik.kagami.storage`) is the single path to stored artifacts; no other
  package touches storage, so a new backend touches no other package. The backend is selected
  from `kagami.storage.type` (`local`, default, or `s3`) in `StorageConfig`. The S3 backend uses
  Spring Cloud AWS (`spring.cloud.aws.*`), which is off by default and enabled only for `s3`.
- `ArtifactLocation` rejects `..`, `~` and absolute paths; an empty path is the repository root.
- No `Path` / `File` in the `StorageService` interface; `delete` removes everything at or under
  a location.
- `lastModified` of a directory entry is optional: object storage has no directory timestamp.
- `BasicToBearerTokenResolver` resolves the password part of Basic auth as a JWT bearer token;
  the username is ignored.
- Token generation requires the USER role - JWT tokens cannot generate new tokens.
- CSRF is partially disabled (`/artifacts/**`, `/token`) to support REST API usage.
- Maven Resolver resolves into a scratch local repository (temp directory) per fetch, removed
  afterwards. The resolved file is copied into `StorageService`; resolver bookkeeping files never
  reach the storage. A scratch directory per fetch means no shared local-repository locking
  between requests.
- Non-standard files (like maven-metadata.xml) are fetched via `RestClient`, not Maven Resolver.

## Development Requirements

### Code Standards

- Use builder pattern if the number of arguments is more than two
- Write javadoc and comments in English
- Spring Java Format enforced via Maven plugin
- All code must pass formatting validation before commit
- Use Java 21 compatible features (avoid Java 22+ specific APIs)
- Use modern Java technics as much as possible like Java Records, Pattern Matching, Text Block
  etc ...
- Be sure to avoid circular references between classes and packages.
- Don't use Lombok.
- Don't use Google Guava.

### Spring Specific Rules

- Always use constructor injection for Spring beans. No `@Autowired` required except for test code.
- Use `RestClient` for external API calls. Don't use `RestTemplate`.
- `RestClient` should be used with injected/autoconfigured `RestClient.Builder`.
- Use `JdbcClient` for database operations. Don't use `JdbcTemplate` except for batch update.
- Use `@Configuration(proxyBeanMethods = false)` for configuration classes to avoid proxying issues.
- Use `@ConfigurationProperties` + Java Records for configuration properties classes. Don't use `@Value` for configuration properties.
- Use `@DefaultValue` for non-null default values in configuration properties classes.

### Package Structure

Main package is `am.ik.kagami`.

Package structure should follow the "package by feature" principle, grouping related classes
together. Not by technical layers.

For DTOs, use inner record classes in the appropriate classes. For example, if you have a
`UserController`, define the request/response class inside that controller class.

`web` package should not be shared across different features. Each feature should have its own `web`
domain objects should be clean and not contain external layers like web or database.

### Testing Strategy

- **Contract Tests**: `StorageServiceContractTest` is the abstract contract every `StorageService`
  backend test must extend
- **E2E Tests**: `BrowserE2ETestBase` drives the built UI with Playwright (Chromium); each backend
  has a subclass
- Use `@TempDir` for filesystem testing, maintain test independence
- All tests must pass consistently; use specific MockMvc expectations
- All tests must pass before completing tasks

### After Task completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"'
```

## important-instruction-reminders

Do what has been asked; nothing more, nothing less.
NEVER create files unless they're absolutely necessary for achieving your goal.
ALWAYS prefer editing an existing file to creating a new one.
NEVER proactively create documentation files (*.md) or README files. Only create documentation files
if explicitly requested by the User.
