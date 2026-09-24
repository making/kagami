# Kagami API Documentation

Kagami is a mirror server for Maven repositories. This document describes the API endpoints available for retrieving and managing artifacts, and the server-rendered web UI.

## Base URL

Artifact operations are available at `/artifacts`. The web UI (home, repository browse and token generation pages) is server-rendered from `/`, `/browse/**` and `/token`.

## Authentication

### Web UI Authentication

The web UI requires authentication through either form-based login or OIDC/OAuth2:

#### Form-based Authentication
- Username: `spring.security.user.name` (default: `demo`)
- Password: `spring.security.user.password` (default: `{noop}demo`)

#### OIDC Authentication
- Configured via OAuth2 client settings
- Access restricted by email patterns in `kagami.authentication.allowed-name-patterns`

The web interface features:
- Styled login and logout pages matching the application design
- Unified header navigation across all pages showing logged-in username
- Easy access to logout functionality and token generation from the header
- Server-rendered pages with Mustache templates and htmx partials

### API Authentication

#### Artifact APIs (`/artifacts/**`)
- Public repositories: No authentication required (GET and HEAD)
- Private repositories: JWT-based authentication. The JWT can be sent either as a
  Bearer token (`Authorization: Bearer <jwt>`) or as the password part of a Basic
  auth header (`Authorization: Basic base64(<username>:<jwt>)`). The username is
  ignored; the password is resolved as the bearer token. The Basic form exists so
  Maven/Gradle clients configured with the standard `<username>`/`<password>`
  server settings work out of the box.
- GET requires the `artifacts:read` scope, DELETE requires the `artifacts:delete`
  scope, and the cache garbage collection endpoints require `artifacts:admin`
  (authenticated web UI sessions with USER role are also accepted).

JWT tokens must be generated through the authenticated web UI. See the [Token Management](#token-management) section for details.

### Endpoints Without Authentication

The following endpoints are accessible without authentication:
- `/login` - Login page
- `/logout` - Logout page
- `/css/**`, `/js/**`, `/fonts/**` - Static assets
- `/favicon.svg` - Favicon
- `/error` - Error pages
- `/actuator/**` - Spring Actuator endpoints (health, metrics, etc.)
- `/.well-known/**` - Well-known endpoints
- `/openid/v1/jwks` - JWKS endpoint for JWT verification
- Public repository artifacts (`/artifacts/{publicRepoId}/**`)

## Content Types

- Requests: `application/x-www-form-urlencoded` for form endpoints
- Responses: `text/plain` for the token endpoint, various MIME types for artifact downloads

## Endpoints

### Artifact Download

#### GET /artifacts/{repositoryId}/{artifactPath}

Download an artifact from the repository. This endpoint serves the actual file content.
If the artifact is not in local storage yet, it is fetched from the remote repository
on demand. HEAD is also supported.

**Parameters:**
- `repositoryId` (path, required): Repository identifier
- `artifactPath` (path, required): Full path to the artifact within the repository

**Example Request:**
```
GET /artifacts/central/org/springframework/spring-core/5.3.21/spring-core-5.3.21.jar
```

**Response:**
- Binary content of the requested file
- Appropriate `Content-Type` header based on file extension
- `Content-Length` header with file size
- `Cache-Control` header: `max-age=31536000, public` for public repositories,
  `max-age=31536000, private` for private repositories
- `Content-Disposition: inline` for XML and text files, including `.properties`; other
  files use `attachment`. The file name is included in the header.

**Status Codes:**
- `200 OK`: File found and returned
- `401 Unauthorized`: Authentication required (for private repository)
- `403 Forbidden`: Token lacks required scope
- `404 Not Found`: Repository, file, or remote artifact not found
- `500 Internal Server Error`: Server error

---

#### DELETE /artifacts/{repositoryId}/{artifactPath}

Delete an artifact or directory from the repository.

**Parameters:**
- `repositoryId` (path, required): Repository identifier
- `artifactPath` (path, required): Full path to the artifact or directory within the repository

**Example Request:**
```
DELETE /artifacts/central/org/springframework/spring-core/5.3.21/spring-core-5.3.21.jar
```

**Response:**
- `204 No Content`: Successful deletion. When the request comes from the web UI (htmx), the
  response carries an `HX-Trigger: refreshEntries` header so the directory listing refreshes itself.
- `401 Unauthorized`: Authentication required (for private repository or if not logged in)
- `403 Forbidden`: Token lacks required scope
- `404 Not Found`: Repository or file not found
- `500 Internal Server Error`: Server error

---

### Cache Garbage Collection

The garbage collection endpoints remove cache-bookkeeping-only directories from one
repository. A candidate directory must contain exactly one of these file sets:

- `maven-metadata.xml` and `maven-metadata.xml.sha1`
- `resolver-status.properties` only

Every file in the candidate must be older than `olderThan`. The default is one hour.
Directories with any other file or subdirectory are left unchanged.

These endpoints require the `artifacts:admin` authority, including for public
repositories. The default `editors` group does not include this authority. The `gc`
path is reserved for this maintenance operation. Bearer-authenticated API calls do not
need a CSRF token; browser-session POST requests do.

#### GET /artifacts/{repositoryId}/gc

Preview eligible directories without changing storage.

**Parameters:**
- `repositoryId` (path, required): Repository identifier
- `olderThan` (query, optional): ISO-8601 duration; defaults to `PT1H`

**Example Request:**
```
GET /artifacts/central/gc?olderThan=PT1H
Authorization: Bearer <jwt>
```

**Response:**
```json
[
  {
    "path": "org/example/missing",
    "lastModified": "2026-09-24T10:00:00Z"
  }
]
```

#### POST /artifacts/{repositoryId}/gc

Collect eligible directories. The operation rechecks each directory before deleting
it and removes only its eligible bookkeeping files, so a concurrent artifact download
is preserved.

**Parameters:**
- `repositoryId` (path, required): Repository identifier
- `olderThan` (query, optional): ISO-8601 duration; defaults to `PT1H`

**Example Request:**
```
POST /artifacts/central/gc?olderThan=PT1H
Authorization: Bearer <jwt>
```

**Response:**
```json
{
  "collectedPaths": ["org/example/missing"],
  "failures": []
}
```

**Status Codes:**
- `200 OK`: Preview or collection completed
- `400 Bad Request`: `olderThan` is negative or invalid
- `401 Unauthorized`: Authentication required
- `403 Forbidden`: Principal lacks `artifacts:admin`
- `404 Not Found`: Repository is not configured
- `500 Internal Server Error`: Storage error while listing or deleting

---

### Token Management

#### POST /token

Generate a JWT token for accessing private repositories. **This endpoint requires USER role authentication**.

**Authentication Required**: Yes - Users must be logged in to the web UI with USER role to generate tokens. JWT tokens cannot be used to generate new tokens.

**Web Interface**: The recommended approach is to use the web-based token generation interface at `/token` which provides:
- Repository selection with checkboxes
- Permission scope selection (artifacts:read, artifacts:delete, artifacts:admin)
- Human-friendly expiration time input (hours, days, months) with 6-month default
- Warning for long-duration tokens (>6 months)
- Copy functionality for generated tokens
- Build tool configuration examples (Maven, Gradle Groovy, Gradle Kotlin) with copy buttons

**Parameters (form-urlencoded):**
- `expires_in` (optional): Token expiration time in hours (default: 3)
- `repositories` (optional): Comma-separated list of repository IDs to access
- `scope` (optional): Comma-separated list of scopes (`artifacts:read`, `artifacts:delete`, `artifacts:admin`)

**Example Request:**
```
POST /token
Content-Type: application/x-www-form-urlencoded
Cookie: JSESSION=<jsession-id>

repositories=spring-enterprise,gemfire&scope=artifacts:read,artifacts:delete&expires_in=24
```

**Response:**
```
eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjgwODAvIiwiYXVkIjoia2FnYW1pIiwiaWF0IjoxNzIzMDE2...
```

The generated JWT token includes:
- `sub` (subject): The authenticated username
- `iss` (issuer): The server URL
- `aud` (audience): "kagami"
- `iat` (issued at): Token creation timestamp
- `exp` (expiration): Token expiration timestamp
- `scope`: Granted permissions
- `repositories`: Accessible repository IDs

**Status Codes:**
- `200 OK`: Token generated successfully
- `401 Unauthorized`: Authentication required
- `403 Forbidden`: USER role required

**Important Notes**: 
- This endpoint requires USER role authentication (not just any authentication)
- JWT tokens cannot be used to generate new JWT tokens - only session-based authentication is accepted
- It is recommended to use the web UI at `/token` to generate tokens rather than calling this API directly

---

## Error Responses

API endpoints return appropriate HTTP status codes. For client errors (4xx) and server errors (5xx), the response body may be empty or contain error details.

For authentication errors (401) on `/artifacts/**`, the response includes a `WWW-Authenticate` header:
- `WWW-Authenticate: Bearer` - for missing authentication
- `WWW-Authenticate: Bearer error="invalid_token", error_description="..."` - for invalid tokens
- `WWW-Authenticate: Bearer error="insufficient_scope", error_description="..."` - for insufficient permissions
- `WWW-Authenticate: Basic realm="Kagami"` - additionally added for non-browser clients
  (browsers are excluded to avoid a native credential dialog)

The generated JWT `scope` and `repositories` claims are serialized as JSON arrays.
