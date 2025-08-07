# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

Kagami is a mirror server of Maven repositories.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
```

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

- `am.ik.kagami`
    - `feature-x` - package for feature X
        - domain objects and services related to feature X
        - `web` - Web controllers and request / response objects for feature X
    - `feature-y` - package for feature Y
        - domain objects and services related to feature Y
        - `web` - Web controllers and request / response objects for feature Y
    - `config` - Configuration classes (e.g., security, cross-cutting concerns)

For DTOs, use inner record classes in the appropriate classes. For example, if you have a
`UserController`, define the request/response class inside that controller class.

`web` package should not be shared across different features. Each feature should have its own `web`
domain objects should be clean and not contain external layers like web or database.

### Testing Strategy

- **Unit Tests**: JUnit 5 with AssertJ for service layer testing
- **Integration Tests**: `@SpringBootTest` + Testcontainers
- **E2E Tests**: Playwright with Testcontainers for full user journey testing
- **Test Data Management**: Database cleanup after each test using `JdbcClient` to maintain test
  independence
- **Test Stability**: All tests must pass consistently; use specific Playwright selectors
- All tests must pass before completing tasks
- Test coverage includes email management, user registration/activation, admin operations, and soft
  delete functionality

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