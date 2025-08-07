# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

Kagami is a mirror server of Maven repositories.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
```

## System Architecture

### Core Components

1. **Storage Layer** (`am.ik.kagami.storage`)
   - `StorageService` interface with minimal methods: `store()`, `retrieve()`, `delete()`
   - `LocalStorageService` implementation using filesystem
   - Designed for future S3 storage implementation
   - Maven Resolver stores artifacts directly in `kagami.storage.path/{repositoryId}/`

2. **Repository Management** (`am.ik.kagami.repository`)
   - `RemoteRepositoryService` uses Maven Resolver API for standard artifacts
   - Separate `RepositorySystemSession` per repository ID to maintain isolation
   - Falls back to `RestClient` for non-standard files (e.g., maven-metadata.xml)
   - Supports Basic authentication and HTTP proxy configuration

3. **Web Layer** 
   - `ArtifactController` (`am.ik.kagami.artifact.web`) handles artifact GET and DELETE operations
   - `BrowserController` (`am.ik.kagami.browser.web`) provides repository browsing REST API
   - URL pattern: `/{repositoryId}/**` for artifact downloads
   - API endpoints: `/api/repositories/**` for repository browsing
   - Returns proper content types based on file extensions

4. **Browser Feature** (`am.ik.kagami.browser`)
   - `BrowserService` provides repository exploration and statistics
   - Repository listing with artifact count, size, and last update timestamps
   - Directory navigation with breadcrumb support
   - File information with checksums and content types
   - Uses `@JsonInclude(NON_NULL)` to exclude null values from JSON responses

### Configuration

- Properties use Map structure for repositories: `kagami.repositories.{id}.url`
- Supports username/password for Basic auth per repository
- HTTP proxy configurable via properties or environment variables (http_proxy, HTTP_PROXY)

## Design Requirements

- **Package**: `am.ik.kagami` - Main package

## Development Requirements

### Prerequisites

- Java 21+

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

Package structure should follow the "package by feature" principle, grouping related classes
together. Not by technical layers.

Current package structure:
- `am.ik.kagami`
    - `artifact.web` - Artifact handling web layer
        - `ArtifactController` - REST endpoints for artifact operations
    - `browser` - Repository browsing feature
        - `BrowserService` - Repository exploration and statistics
        - `web.BrowserController` - REST API for repository browsing
    - `repository` - Remote repository management
        - `RemoteRepositoryService` - Maven Resolver integration
    - `storage` - Storage abstraction layer
        - `StorageService` - Minimal interface (store/retrieve/delete)
        - `LocalStorageService` - Filesystem implementation
    - `config` - Configuration classes (e.g., security, cross-cutting concerns)

For DTOs, use inner record classes in the appropriate classes. For example, if you have a
`UserController`, define the request/response class inside that controller class.

`web` package should not be shared across different features. Each feature should have its own `web`
domain objects should be clean and not contain external layers like web or database.

### Testing Strategy

- **Unit Tests**: JUnit 5 with AssertJ for service layer testing
- **Integration Tests**: `@SpringBootTest` + Testcontainers for full application context
- **Test Data Management**: Use `@TempDir` for filesystem testing, maintain test independence
- **Test Stability**: All tests must pass consistently; use specific MockMvc expectations
- All tests must pass before completing tasks
- Test coverage includes artifact operations, repository browsing, and API endpoints

### After Task completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"'
```

## Important Architecture Decisions

1. **Maven Resolver Integration**
   - Each repository has its own `RepositorySystemSession` to maintain isolation
   - Maven Resolver's local repository is set to `kagami.storage.path/{repositoryId}/`
   - This eliminates duplicate storage between Maven Resolver and Kagami
   - Non-standard files (like maven-metadata.xml) are fetched via RestClient

2. **Storage Abstraction**
   - Minimal interface design for easy S3 migration
   - No path-specific return values that would be meaningless for cloud storage
   - Resource-based retrieval allows flexible implementation

3. **Configuration Design**
   - Map-based repository configuration for better organization
   - Per-repository authentication support
   - Flexible proxy configuration (properties + environment variables)

4. **Browser API Design**
   - REST API follows standard conventions with `/api` prefix
   - JSON responses exclude null values using `@JsonInclude(NON_NULL)`
   - Directory navigation uses breadcrumb pattern with `parentPath`
   - File information includes size, timestamps, and checksums
   - Comprehensive API documentation provided for frontend implementation

## important-instruction-reminders

Do what has been asked; nothing more, nothing less.
NEVER create files unless they're absolutely necessary for achieving your goal.
ALWAYS prefer editing an existing file to creating a new one.
NEVER proactively create documentation files (*.md) or README files. Only create documentation files
if explicitly requested by the User.