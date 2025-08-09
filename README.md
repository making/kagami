# Kagami 🪞

A simple Maven repository mirror server built with Spring Boot. Kagami (鏡, meaning "mirror" in Japanese) provides efficient caching and proxying of Maven artifacts from multiple remote repositories.

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/e352db90-9fe9-4884-92fe-ac93f8621485" />

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/48f46307-7042-4b8b-958a-52c433e24b83" />

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/954b34e6-4efa-4d8f-adeb-b270415224e7" />

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/97883489-cf38-46a3-939e-3e118265a014" />

## Features

- **Local Caching**: Automatically caches artifacts from remote repositories to reduce download times
- **Multiple Repository Support**: Configure multiple remote repositories with individual settings
- **Private Repository Support**: JWT-based authentication for secure repository access
- **Basic Authentication**: Support for repositories requiring username/password authentication
- **HTTP Proxy Support**: Configure HTTP proxy for outbound connections
- **REST API**: Simple REST endpoints for artifact retrieval and cache management
- **Web Dashboard**: Modern React-based UI for repository browsing and management with unified header navigation
- **Authentication**: Form-based authentication for web UI access with styled login/logout pages
- **Token Management**: Web-based JWT token generation with configurable expiration, permissions, and build tool configuration examples
- **User Interface**: Consistent header across all pages showing logged-in username, logout functionality, and token generation access
- **Security Features**: OAuth2 Resource Server with JWT tokens, repository-specific access control, role-based token generation, CSRF protection partially disabled for API usage

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

4. Log in to the web dashboard:
   - Default username: `demo`
   - Default password: `demo`

5. Access artifacts via HTTP:
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

### Private Repository Configuration

```properties
# Mark repository as private (requires JWT authentication)
kagami.repositories.private-repo.url=https://internal.repo.com/maven2
kagami.repositories.private-repo.is-private=true

# JWT key pair configuration
kagami.jwt.private-key=classpath:kagami-private.pem
kagami.jwt.public-key=classpath:kagami-public.pem
```

To generate the JWT key pair, run the following commands:

```bash
# Generate RSA private key
openssl genrsa -out private.pem 2048

# Extract public key
openssl rsa -in private.pem -outform PEM -pubout -out kagami-public.pem

# Convert private key to PKCS#8 format
openssl pkcs8 -topk8 -inform PEM -in private.pem -out kagami-private.pem -nocrypt

# Clean up temporary file
rm -f private.pem
```

Place the generated `kagami-private.pem` and `kagami-public.pem` files in your `src/main/resources` directory.

### Web UI Authentication

```properties
# Configure web UI authentication (default: demo/demo)
spring.security.user.name=your-username
spring.security.user.password={noop}plainpassword
# For production, use encoded password:
# spring.security.user.password={bcrypt}$2a$10$...
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

### Public Repository Access

```xml
<settings>
  <profiles>
    <profile>
      <id>kagami-public</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <repositories>
        <repository>
          <id>kagami-central</id>
          <name>Kagami Public Repository</name>
          <url>http://localhost:8080/artifacts/central</url>
          <snapshots>
            <enabled>false</enabled>
          </snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>kagami-central</id>
          <name>Kagami Public Repository</name>
          <url>http://localhost:8080/artifacts/central</url>
          <snapshots>
            <enabled>false</enabled>
          </snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  
  <!-- ALTERNATIVE: Mirror configuration -->
  <!-- Use mirrors when you want to redirect ALL Maven repository requests through Kagami -->
  <!-- This is useful for: -->
  <!-- - Corporate environments where all external access must go through a proxy -->
  <!-- - Offline environments where only Kagami has access to external repositories -->
  <!-- - Performance optimization when Kagami has better network access to upstream repos -->
  <!--
  <mirrors>
    <mirror>
      <id>kagami</id>
      <mirrorOf>*</mirrorOf>
      <name>Kagami Mirror</name>
      <url>http://localhost:8080/artifacts/central</url>
    </mirror>
  </mirrors>
  -->
</settings>
```

### Private Repository Access

For private repositories, generate a JWT token using the web interface:

1. Log in to the web dashboard at `http://localhost:8080`
2. Click "Generate Token" in the header navigation
3. Select repositories and permissions using checkboxes
4. Set token expiration with human-friendly units (default: 6 months)
5. Click "Generate Token" and copy the generated JWT
6. Use the provided Maven, Gradle Groovy, or Gradle Kotlin configuration examples

**Note**: JWT tokens cannot be used to generate new tokens. You must use the web interface with your username/password credentials.

Then configure Maven with the JWT token:

```xml
<settings>
  <servers>
    <server>
      <id>kagami-private</id>
      <username>kagami</username>
      <password>YOUR_JWT_TOKEN</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>kagami-private</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <repositories>
        <repository>
          <id>kagami-private</id>
          <name>Kagami Private Repository</name>
          <url>http://localhost:8080/artifacts/private-repo</url>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>kagami-private</id>
          <name>Kagami Private Repository</name>
          <url>http://localhost:8080/artifacts/private-repo</url>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  
  <!-- ALTERNATIVE: Mirror configuration -->
  <!-- Use mirrors when you want to redirect ALL Maven repository requests through Kagami -->
  <!-- This is useful for: -->
  <!-- - Corporate environments where all external access must go through a proxy -->
  <!-- - Offline environments where only Kagami has access to external repositories -->
  <!-- - Performance optimization when Kagami has better network access to upstream repos -->
  <!--
  <mirrors>
    <mirror>
      <id>kagami-private</id>
      <mirrorOf>*</mirrorOf>
      <name>Kagami Private Mirror</name>
      <url>http://localhost:8080/artifacts/private-repo</url>
    </mirror>
  </mirrors>
  -->
</settings>
```

### Gradle Configuration

#### Public Repository

```gradle
repositories {
    maven {
        url 'http://localhost:8080/artifacts/central'
    }
}
```

#### Private Repository

```gradle
repositories {
    maven {
        url 'http://localhost:8080/artifacts/private-repo'
        credentials {
            username = 'kagami'
            password = 'YOUR_JWT_TOKEN'
        }
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
# For security troubleshooting:
logging.level.org.springframework.security=DEBUG
```

### Health Check

The application provides health check endpoints via Spring Actuator:

```bash
# Health status
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus
```

## Roadmap

The following features are planned for future releases:

### Authentication & Security
- **OIDC Authentication**: OpenID Connect integration for dashboard access (in addition to current form-based auth)

### Storage Backends
- **S3 Storage**: Amazon S3 and S3-compatible storage backends (MinIO, etc.)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

