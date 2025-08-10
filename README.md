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
- **HTTP Proxy Support**: Configure HTTP proxy for outbound connections
- **REST API**: Simple REST endpoints for artifact retrieval and cache management
- **Web Dashboard**: Modern React-based UI for repository browsing and management with unified header navigation
- **Authentication**: Form-based authentication or OIDC/OAuth2 login for web UI access with styled login/logout pages
- **Token Management**: Web-based JWT token generation with configurable expiration, permissions, and build tool configuration examples
- **User Interface**: Consistent header across all pages showing logged-in username, logout functionality, and token generation access
- **Security Features**: OAuth2 Resource Server with JWT tokens, repository-specific access control, role-based token generation, CSRF protection partially disabled for API usage
- **OIDC Support**: OpenID Connect authentication with multiple identity providers (Google, Microsoft Entra ID, etc.)

## Quick Start

### Option 1: Using Docker

The fastest way to get started with Kagami is using the pre-built Docker image:

```bash
# Run Kagami with Docker
docker run --rm --pull always -p 8080:8080 \
  -v /tmp/kagami:/var/kagami/storage \
  -e kagami.storage.path=/var/kagami/storage \
  -e kagami.repositories.central.url=https://repo.maven.apache.org/maven2 \
  ghcr.io/making/kagami:jvm
```

**Access the application:**
1. Open http://localhost:8080 in your browser
2. Log in with default credentials:
   - Username: `demo`
   - Password: `demo`
3. Access artifacts: `wget http://localhost:8080/artifacts/central/org/springframework/spring-core/6.0.0/spring-core-6.0.0.jar`

**Docker Configuration Options:**

```bash
# With private repository with authentication
docker run --rm --pull always -p 8080:8080 \
  -v /tmp/kagami:/var/kagami/storage \
  -e kagami.storage.path=/var/kagami/storage \
  -e kagami.repositories.spring-enterprise.url=https://packages.broadcom.com/artifactory/spring-enterprise \
  -e kagami.repositories.spring-enterprise.username=your-bc-username \
  -e kagami.repositories.spring-enterprise.password=your-bc-token \
  -e kagami.repositories.spring-enterprise.is-private=true \
  ghcr.io/making/kagami:jvm

# With custom authentication
docker run --rm --pull always -p 8080:8080 \
  -v /tmp/kagami:/var/kagami/storage \
  -e kagami.storage.path=/var/kagami/storage \
  -e kagami.repositories.central.url=https://repo.maven.apache.org/maven2 \
  -e spring.security.user.name=admin \
  -e spring.security.user.password='{noop}mypassword' \
  ghcr.io/making/kagami:jvm
```

**Using Docker Compose:**

For production deployments with JWT token support, create a `docker-compose.yml` file:

```yaml
services:
  kagami:
    image: ghcr.io/making/kagami:jvm
    pull_policy: always
    ports:
      - "8080:8080"
    volumes:
      - ./kagami-storage:/var/kagami/storage
      - ./kagami-private.pem:/etc/kagami/kagami-private.pem:ro
      - ./kagami-public.pem:/etc/kagami/kagami-public.pem:ro
    environment:
      kagami.storage.path: /var/kagami/storage
      kagami.repositories.central.url: https://repo.maven.apache.org/maven2
      # Configure private repository (requires JWT keys)
      kagami.repositories.private-repo.url: https://internal.repo.com/maven2
      kagami.repositories.private-repo.is-private: true
      # JWT key configuration
      kagami.jwt.private-key: file:/etc/kagami/kagami-private.pem
      kagami.jwt.public-key: file:/etc/kagami/kagami-public.pem
      # Optional: Custom authentication
      # spring.security.user.name: admin
      # spring.security.user.password: '{noop}mypassword'
```

**Before running:**
1. Generate JWT key pair following the instructions in [Private Repository Configuration](#private-repository-configuration)
2. Place `kagami-private.pem` and `kagami-public.pem` in the same directory as your `docker-compose.yml`
3. Set appropriate file permissions:
   ```bash
   mkdir -p ./kagami-storage
   chmod a+w ./kagami-storage
   chmod a+r ./kagami-private.pem ./kagami-public.pem
   ```
4. Run with: `docker compose up -d`

**Note:** The Kagami container runs as user ID 1002 for security. Mounted files and directories must have appropriate permissions for this user to read/write them.

### Option 2: Building from Source

#### Prerequisites

- Java 21 or later

#### Running

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
wget http://localhost:8080/artifacts/central/org/springframework/spring-core/6.0.0/spring-core-6.0.0.jar
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

#### Simple Authentication (Form-based)

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/774de4f6-9a80-47b0-bc9e-e9f47061707e" />

```properties
# Configure web UI authentication (default: demo/demo)
kagami.authentication.type=simple
spring.security.user.name=your-username
spring.security.user.password={noop}plainpassword
# For production, use encoded password:
# spring.security.user.password={bcrypt}$2a$10$...
```

Only one user can be configured with this method. For multiple users, use OIDC authentication.

#### OIDC Authentication

<img width="1024" alt="Image" src="https://github.com/user-attachments/assets/aa3e57f2-726f-4111-9b69-50458cd0e184" />

Configure OIDC authentication for enterprise single sign-on:

```properties
# Enable OIDC authentication
kagami.authentication.type=oidc

# Restrict access to specific email patterns (regex)
kagami.authentication.allowed-name-patterns=.*@example\\.com,.*@example\\.org

# Configure Google as identity provider
spring.security.oauth2.client.provider.google.issuer-uri=https://accounts.google.com
spring.security.oauth2.client.provider.google.user-name-attribute=email
spring.security.oauth2.client.registration.google.client-id=your-google-client-id
spring.security.oauth2.client.registration.google.client-secret=your-google-client-secret
spring.security.oauth2.client.registration.google.client-name=Google
spring.security.oauth2.client.registration.google.scope=openid,email

# Configure Microsoft Entra ID (formerly Azure AD)
spring.security.oauth2.client.provider.microsoft-entra-id.issuer-uri=https://sts.windows.net/{tenant-id}/
spring.security.oauth2.client.provider.microsoft-entra-id.user-name-attribute=email
spring.security.oauth2.client.registration.microsoft-entra-id.client-id=your-client-id
spring.security.oauth2.client.registration.microsoft-entra-id.client-secret=your-client-secret
spring.security.oauth2.client.registration.microsoft-entra-id.client-name=Microsoft Entra ID
spring.security.oauth2.client.registration.microsoft-entra-id.scope=openid,email
```

**Notes**:
- When OIDC is enabled, users will see provider-specific login buttons instead of username/password fields
- The `allowed-name-patterns` property restricts access to users whose email matches the specified patterns
- Multiple identity providers can be configured simultaneously
- Users must have matching email patterns to be granted USER role access

See https://docs.spring.io/spring-boot/reference/web/spring-security.html#web.security.oauth2.client for more details on configuring OIDC authentication.

### HTTP Proxy Configuration (Experimental)

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
      <configuration>
        <httpHeaders>
          <property>
            <name>Authorization</name>
            <value>Bearer YOUR_JWT_TOKEN</value>
          </property>
        </httpHeaders>
      </configuration>
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

**Groovy DSL ($HOME/.gradle/init.gradle)**
```gradle
// $HOME/.gradle/init.gradle - Groovy DSL version

def repoUrl = "http://localhost:8080/artifacts/private-repo"
def repoToken = "Bearer YOUR_JWT_TOKEN"

// For regular dependencies (legacy projects)
allprojects {
    repositories {
        maven {
            url = repoUrl
            allowInsecureProtocol = true
            authentication {
                header(HttpHeaderAuthentication)
            }
            credentials(HttpHeaderCredentials) {
                name = "Authorization"
                value = repoToken
            }
        }
        mavenCentral() // fallback
    }
}

// Configure settings.gradle
settingsEvaluated { settings ->
    // For plugin resolution
    settings.pluginManagement {
        repositories {
            maven {
                url = repoUrl
                allowInsecureProtocol = true
                authentication {
                    header(HttpHeaderAuthentication)
                }
                credentials(HttpHeaderCredentials) {
                    name = "Authorization"
                    value = repoToken
                }
            }
            gradlePluginPortal() // fallback
            mavenCentral() // fallback
        }
    }
    
    // Dependency resolution management (Gradle 6.8+)
    settings.dependencyResolutionManagement {
        // Ignore repositories defined in build.gradle
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
        
        repositories {
            maven {
                url = repoUrl
                allowInsecureProtocol = true
                authentication {
                    header(HttpHeaderAuthentication)
                }
                credentials(HttpHeaderCredentials) {
                    name = "Authorization"
                    value = repoToken
                }
            }
            mavenCentral()
        }
    }
}
```

**Kotlin DSL ($HOME/.gradle/init.gradle.kts)**
```kotlin
// $HOME/.gradle/init.gradle.kts - Kotlin DSL version

import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication
import org.gradle.kotlin.dsl.*

val repoUrl = "http://localhost:8080/artifacts/private-repo"
val repoToken = "Bearer YOUR_JWT_TOKEN"

// Extension function to configure repository
fun RepositoryHandler.addKagamiRepository() {
    maven {
        url = uri(repoUrl)
        isAllowInsecureProtocol = true
        authentication {
            create<HttpHeaderAuthentication>("header")
        }
        credentials(HttpHeaderCredentials::class) {
            name = "Authorization"
            value = repoToken
        }
    }
}

// For regular dependencies (legacy projects)
allprojects {
    repositories {
        addKagamiRepository()
        mavenCentral() // fallback
    }
}

// Configure settings.gradle
settingsEvaluated {
    // For plugin resolution
    pluginManagement {
        repositories {
            addKagamiRepository()
            gradlePluginPortal() // fallback
            mavenCentral() // fallback
        }
    }
    
    // Dependency resolution management (Gradle 6.8+)
    dependencyResolutionManagement {
        // Ignore repositories defined in build.gradle
        @Suppress("UnstableApiUsage")
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
        
        repositories {
            addKagamiRepository()
            mavenCentral() // fallback
        }
    }
}
```

## Building from Source

### Build and Test

```bash
# Build the application
./mvnw clean package

# Run all tests
./mvnw test

# Run with development profile
./mvnw spring-boot:run
```

### Creating Docker Images

Kagami supports creating optimized Docker images using Spring Boot's buildpacks integration:

```bash
# Create Docker image using buildpacks (requires Docker)
./mvnw spring-boot:build-image

# The generated image will be tagged as: kagami:0.0.1-SNAPSHOT
# You can run it with:
docker run --pull always -p 8080:8080 \
  -v /tmp/kagami:/var/kagami/storage \
  -e kagami.storage.path=/var/kagami/storage \
  -e kagami.repositories.central.url=https://repo.maven.apache.org/maven2 \
  kagami:0.0.1-SNAPSHOT

# Custom image name and tag
./mvnw spring-boot:build-image -Dspring-boot.build-image.imageName=myregistry/kagami:latest
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

### Storage Backends
- **S3 Storage**: Amazon S3 and S3-compatible storage backends (MinIO, etc.)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

