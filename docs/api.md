# Kagami API Documentation

Kagami is a mirror server for Maven repositories. This document describes the REST API endpoints available for browsing repositories and retrieving artifacts.

## Base URL

Repository browsing endpoints are available at `/repositories` and artifact operations at `/artifacts`.

## Authentication

Public repositories require no authentication. Private repositories require JWT-based authentication using either:
- Bearer token in Authorization header: `Authorization: Bearer <jwt>`
- Basic authentication with JWT as password: `Authorization: Basic <base64(username:jwt)>`

See the [Token Management](#token-management) section for details on obtaining JWTs.

## Content Types

- Requests: `application/json`
- Responses: `application/json` for API endpoints, various MIME types for artifact downloads

## Endpoints

### Repository Management

#### GET /repositories

Get a list of all configured repositories with their statistics.

**Response:**
```json
{
  "repositories": [
    {
      "id": "central",
      "url": "https://repo.maven.apache.org/maven2",
      "artifactCount": 1234567,
      "totalSize": 98765432100,
      "lastUpdated": "2025-08-07T10:30:00Z",
      "isPrivate": false
    },
    {
      "id": "private-repo",
      "url": "https://internal.repo.com/maven2",
      "artifactCount": 52341,
      "totalSize": 1048576000,
      "lastUpdated": "2025-08-07T12:15:30Z",
      "isPrivate": true
    }
  ]
}
```

**Response Fields:**
- `repositories` (array): List of repository information
  - `id` (string): Repository identifier
  - `url` (string): Remote repository URL
  - `artifactCount` (number): Number of artifacts in the repository
  - `totalSize` (number): Total size in bytes
  - `lastUpdated` (string, nullable): Last update timestamp in ISO 8601 format
  - `isPrivate` (boolean): Whether the repository requires authentication

**Status Codes:**
- `200 OK`: Success

---

### Repository Browsing

#### GET /repositories/{repositoryId}/browse

Browse the contents of a repository at a specific path.

**Parameters:**
- `repositoryId` (path, required): Repository identifier
- `path` (query, optional): Path within the repository. If not provided, returns root directory contents.

**Example Request:**
```
GET /repositories/central/browse?path=org/springframework
```

**Response:**
```json
{
  "repositoryId": "central",
  "currentPath": "org/springframework",
  "parentPath": "org",
  "entries": [
    {
      "name": "spring-core",
      "type": "directory",
      "path": "org/springframework/spring-core",
      "lastModified": "2025-08-07T10:30:00Z"
    },
    {
      "name": "spring-core-5.3.21.jar",
      "type": "file",
      "path": "org/springframework/spring-core-5.3.21.jar",
      "size": 1048576,
      "lastModified": "2025-08-07T10:30:00Z"
    }
  ]
}
```

**Response Fields:**
- `repositoryId` (string): Repository identifier
- `currentPath` (string): Current path being browsed
- `parentPath` (string, nullable): Parent path, null for root directory
- `entries` (array): List of directory entries
  - `name` (string): File or directory name
  - `type` (string): Either "file" or "directory"
  - `path` (string): Full path relative to repository root
  - `size` (number, optional): File size in bytes. Only present for files, omitted for directories.
  - `lastModified` (string): Last modification timestamp in ISO 8601 format

**Status Codes:**
- `200 OK`: Success
- `400 Bad Request`: Invalid repository ID or path
- `500 Internal Server Error`: Server error

---

#### GET /repositories/{repositoryId}/info

Get detailed information about a specific file.

**Parameters:**
- `repositoryId` (path, required): Repository identifier
- `path` (query, required): File path within the repository

**Example Request:**
```
GET /repositories/central/info?path=org/springframework/spring-core/5.3.21/spring-core-5.3.21.jar
```

**Response:**
```json
{
  "repositoryId": "central",
  "path": "org/springframework/spring-core/5.3.21/spring-core-5.3.21.jar",
  "name": "spring-core-5.3.21.jar",
  "type": "file",
  "size": 1048576,
  "lastModified": "2025-08-07T10:30:00Z",
  "contentType": "application/java-archive",
  "sha1": "da39a3ee5e6b4b0d3255bfef95601890afd80709",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

**Response Fields:**
- `repositoryId` (string): Repository identifier
- `path` (string): File path within the repository
- `name` (string): File name
- `type` (string): Always "file"
- `size` (number): File size in bytes
- `lastModified` (string): Last modification timestamp in ISO 8601 format
- `contentType` (string): MIME type of the file
- `sha1` (string, optional): SHA-1 checksum if available
- `sha256` (string, optional): SHA-256 checksum if available

**Content Types:**
- `.jar` files: `application/java-archive`
- `.pom`, `.xml` files: `application/xml`
- `.sha1`, `.sha256`, `.md5` files: `text/plain`
- `.asc` files: `application/pgp-signature`
- Other files: `application/octet-stream`

**Status Codes:**
- `200 OK`: Success
- `400 Bad Request`: Invalid repository ID, missing path, or file not found
- `500 Internal Server Error`: Server error

---

### Artifact Download

#### GET /artifacts/{repositoryId}/{artifactPath}

Download an artifact from the repository. This endpoint serves the actual file content.

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
- `Last-Modified` header with file modification timestamp

**Status Codes:**
- `200 OK`: File found and returned
- `401 Unauthorized`: Authentication required for private repository
- `403 Forbidden`: Token lacks required scope
- `404 Not Found`: Repository or file not found
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
- `204 No Content`: Successful deletion
- `401 Unauthorized`: Authentication required for private repository
- `403 Forbidden`: Token lacks required scope
- `404 Not Found`: Repository or file not found
- `500 Internal Server Error`: Server error

---

### Token Management

#### POST /token

Generate a JWT token for accessing private repositories.

**Parameters (form-urlencoded):**
- `expires_in` (optional): Token expiration time in hours (default: 3)
- `repositories` (optional): Comma-separated list of repository IDs to access
- `scope` (optional): Comma-separated list of scopes (`artifacts:read`, `artifacts:delete`)

**Example Request:**
```
POST /token
Content-Type: application/x-www-form-urlencoded

repositories=spring-enterprise,gemfire&scope=artifacts:read,artifacts:delete&expires_in=24
```

**Response:**
```
eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjgwODAvIiwiYXVkIjoia2FnYW1pIiwiaWF0IjoxNzIzMDE2...
```

**Status Codes:**
- `200 OK`: Token generated successfully

---

## Error Responses

API endpoints return appropriate HTTP status codes. For client errors (4xx) and server errors (5xx), the response body may be empty or contain error details.

For authentication errors (401), the response includes a `WWW-Authenticate` header:
- `WWW-Authenticate: Bearer` - for missing authentication
- `WWW-Authenticate: Bearer error="invalid_token", error_description="..."` - for invalid tokens
- `WWW-Authenticate: Bearer error="insufficient_scope", error_description="..."` - for insufficient permissions

## Examples

### Frontend Implementation Guidelines

1. **Repository List**: Use `GET /repositories` to populate a repository selector.

2. **Directory Navigation**: Use `GET /repositories/{id}/browse` with the `path` parameter to implement breadcrumb navigation and directory browsing.

3. **File Information**: Use `GET /repositories/{id}/info` to display detailed file information in a sidebar or modal.

4. **File Download**: Use `GET /artifacts/{repositoryId}/{artifactPath}` to provide direct download links.

5. **Path Handling**: 
   - Use `parentPath` for "up" navigation
   - Use entry `path` values for drilling down into directories
   - Handle null `parentPath` to disable "up" navigation at root level

6. **Size Display**: 
   - Files will always have a `size` field
   - Directories will not have a `size` field in the JSON response
   - Format file sizes appropriately (KB, MB, GB)

7. **Timestamps**: All timestamps are in ISO 8601 format and should be parsed and displayed in the user's local timezone.

### Sample Frontend Flow

1. Load repository list on application start
2. Allow user to select a repository
3. Browse repository root (`path` parameter omitted)
4. Display breadcrumbs based on `currentPath`
5. Show entries with appropriate icons for files vs directories
6. Handle clicks on directories to navigate deeper
7. Handle clicks on files to show detailed information or download
8. Provide "parent" navigation using `parentPath`