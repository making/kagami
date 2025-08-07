# Kagami 🪞

A simple Maven repository mirror server built with Spring Boot. Kagami (鏡, meaning "mirror" in Japanese) provides efficient caching and proxying of Maven artifacts from multiple remote repositories.

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/a1dc0529-2d26-4b30-8742-107593ee37ee" />

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/1c519009-9e6c-4c40-bca0-7998d8f89ce7" />

## Features

- **Local Caching**: Automatically caches artifacts from remote repositories to reduce download times
- **Multiple Repository Support**: Configure multiple remote repositories with individual settings
- **Basic Authentication**: Support for repositories requiring username/password authentication
- **HTTP Proxy Support**: Configure HTTP proxy for outbound connections
- **REST API**: Simple REST endpoints for artifact retrieval and cache management
- **Web Dashboard**: Modern React-based UI for repository browsing and management

## Quick Start

### Prerequisites

- Java 21 or later

### Running

1. Clone the repository:
```bash
git clone https://github.com/making/kagami.git
cd kagami
```

2. Build and run:
```bash
./mvnw spring-boot:run
```

3. Access the web dashboard:
```bash
open http://localhost:8080
```

4. Access artifacts via HTTP:
```bash
curl http://localhost:8080/artifacts/central/org/springframework/spring-core/6.0.0/spring-core-6.0.0.jar
```

## Configuration

Configure repositories and settings in `application.properties`:

### Basic Repository Configuration

```properties
# Storage path for cached artifacts
kagami.storage.path=/var/kagami/storage

# Public repositories
kagami.repositories.central.url=https://repo.maven.apache.org/maven2
kagami.repositories.jcenter.url=https://jcenter.bintray.com
```

### Repository with Authentication

```properties
# Private repository with Basic authentication
kagami.repositories.private.url=https://private.repo.example.com/maven2
kagami.repositories.private.username=your-username
kagami.repositories.private.password=your-password
```

### HTTP Proxy Configuration

```properties
# Configure HTTP proxy
kagami.proxy.url=http://proxy.company.com:8080
```

Alternatively, use environment variables:
```bash
export http_proxy=http://proxy.company.com:8080
export HTTP_PROXY=http://proxy.company.com:8080
```

## API Usage

See the [API documentation](docs/api.md) for details on available endpoints.

## Maven Client Configuration

Configure your Maven settings to use Kagami as a mirror:

### settings.xml

```xml
<settings>
  <mirrors>
    <mirror>
      <id>kagami</id>
      <mirrorOf>*</mirrorOf>
      <name>Kagami Mirror</name>
      <url>http://localhost:8080/artifacts/central</url>
    </mirror>
  </mirrors>
</settings>
```

### Gradle Configuration

```gradle
repositories {
    maven {
        url 'http://localhost:8080/artifacts/central'
    }
}
```

## Building from Source

```bash
# Build
./mvnw clean package

# Run tests
./mvnw test

# Create Docker image (if Docker is available)
./mvnw spring-boot:build-image
```

### Logging

Enable debug logging for troubleshooting:

```properties
logging.level.am.ik.kagami=DEBUG
logging.level.org.eclipse.aether=DEBUG
```

## Roadmap

The following features are planned for future releases:

### Authentication & Security
- **OIDC Authentication**: OpenID Connect integration for dashboard access
- **API Token Management**: Generate and manage API tokens for programmatic access
- **Enhanced Repository Authentication**: Support for JWT tokens in addition to Basic auth
- **Role-based Access Control**: Fine-grained permissions for different user roles

### Storage Backends
- **S3 Storage**: Amazon S3 and S3-compatible storage backends (MinIO, etc.)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

