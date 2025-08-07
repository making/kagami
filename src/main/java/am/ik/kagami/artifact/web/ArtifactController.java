package am.ik.kagami.artifact.web;

import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Maven artifact operations
 */
@RestController
@RequestMapping("/artifacts")
public class ArtifactController {

	private final StorageService storageService;

	private final RemoteRepositoryService remoteRepositoryService;

	public ArtifactController(StorageService storageService, RemoteRepositoryService remoteRepositoryService) {
		this.storageService = storageService;
		this.remoteRepositoryService = remoteRepositoryService;
	}

	@GetMapping("/{repositoryId}/**")
	public ResponseEntity<Resource> getArtifact(@PathVariable String repositoryId, HttpServletRequest request) {
		// Validate repository
		if (!this.remoteRepositoryService.isRepositoryConfigured(repositoryId)) {
			return ResponseEntity.notFound().build();
		}
		// Extract artifact path from request
		String artifactPath = extractArtifactPath(request, repositoryId);

		// Try to retrieve from local storage first
		Resource resource = this.storageService.retrieve(repositoryId, artifactPath);

		if (resource == null) {
			// Not in local storage, try to fetch from remote
			boolean fetched = this.remoteRepositoryService.fetchArtifact(repositoryId, artifactPath);
			if (fetched) {
				resource = this.storageService.retrieve(repositoryId, artifactPath);
			}
		}

		if (resource != null && resource.exists()) {
			try {
				return ResponseEntity.ok()
					.contentType(determineContentType(artifactPath))
					.contentLength(resource.contentLength())
					.cacheControl(CacheControl.maxAge(Duration.ofSeconds(31536000)).cachePublic())
					.body(resource);
			}
			catch (IOException e) {
				return ResponseEntity.internalServerError().build();
			}
		}

		return ResponseEntity.notFound().build();
	}

	@DeleteMapping("/{repositoryId}/**")
	public ResponseEntity<Void> deleteArtifact(@PathVariable String repositoryId, HttpServletRequest request) {
		// Validate repository
		if (!this.remoteRepositoryService.isRepositoryConfigured(repositoryId)) {
			return ResponseEntity.notFound().build();
		}
		// Extract artifact path from request
		String artifactPath = extractArtifactPath(request, repositoryId);

		try {
			boolean deleted = this.storageService.delete(repositoryId, artifactPath);
			if (deleted) {
				return ResponseEntity.noContent().build();
			}
			else {
				return ResponseEntity.notFound().build();
			}
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	private String extractArtifactPath(HttpServletRequest request, String repositoryId) {
		String fullPath = request.getRequestURI();
		// Remove the leading "/artifacts/" and repository ID to get the artifact path
		String prefix = "/artifacts/" + repositoryId + "/";
		if (fullPath.startsWith(prefix)) {
			return fullPath.substring(prefix.length());
		}
		// Fallback - this shouldn't happen with proper routing
		return fullPath.substring(fullPath.indexOf(repositoryId) + repositoryId.length() + 1);
	}

	private MediaType determineContentType(String artifactPath) {
		if (artifactPath.endsWith(".jar")) {
			return MediaType.parseMediaType("application/java-archive");
		}
		else if (artifactPath.endsWith(".pom") || artifactPath.endsWith(".xml")) {
			return MediaType.APPLICATION_XML;
		}
		else if (artifactPath.endsWith(".sha1") || artifactPath.endsWith(".md5") || artifactPath.endsWith(".sha256")
				|| artifactPath.endsWith(".sha512")) {
			return MediaType.TEXT_PLAIN;
		}
		else if (artifactPath.endsWith(".asc")) {
			return MediaType.parseMediaType("application/pgp-signature");
		}
		else {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

}