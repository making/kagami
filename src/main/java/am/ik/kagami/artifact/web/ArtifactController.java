package am.ik.kagami.artifact.web;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.Repository;
import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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

	private final Map<String, Repository> repositories;

	public ArtifactController(StorageService storageService, RemoteRepositoryService remoteRepositoryService,
			KagamiProperties properties) {
		this.storageService = storageService;
		this.remoteRepositoryService = remoteRepositoryService;
		this.repositories = properties.repositories();
	}

	@GetMapping("/{repositoryId}/**")
	public ResponseEntity<Resource> getArtifact(@PathVariable String repositoryId, HttpServletRequest request) {
		// Validate repository
		if (!this.remoteRepositoryService.isRepositoryConfigured(repositoryId)) {
			return ResponseEntity.notFound().build();
		}
		Repository repository = this.repositories.get(repositoryId);
		if (repository == null) {
			// This should not happen
			return ResponseEntity.notFound().build();
		}
		// Extract artifact path from request
		String artifactPath = extractArtifactPath(request, repositoryId);
		ArtifactLocation location = new ArtifactLocation(repositoryId, artifactPath);

		// Try to retrieve from local storage first
		Optional<Resource> retrieved = this.storageService.retrieve(location);

		if (retrieved.isEmpty()) {
			// Not in local storage, try to fetch from remote
			boolean fetched = this.remoteRepositoryService.fetchArtifact(location);
			if (fetched) {
				retrieved = this.storageService.retrieve(location);
			}
		}

		if (retrieved.isPresent() && retrieved.get().exists()) {
			Resource resource = retrieved.get();
			try {
				MediaType contentType = determineContentType(artifactPath);
				String disposition = MediaType.APPLICATION_XML.equals(contentType)
						|| MediaType.TEXT_PLAIN.equals(contentType) ? "inline" : "attachment";
				CacheControl cacheControl = CacheControl.maxAge(Duration.ofSeconds(31536000));
				return ResponseEntity.ok()
					.contentType(contentType)
					.contentLength(resource.contentLength())
					.cacheControl(repository.isPrivate() ? cacheControl.cachePrivate() : cacheControl.cachePublic())
					.header(HttpHeaders.CONTENT_DISPOSITION,
							"%s;filename=%s".formatted(disposition, resource.getFilename()))
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
			boolean deleted = this.storageService.delete(new ArtifactLocation(repositoryId, artifactPath));
			if (deleted) {
				// 204 is a "no swap" status for htmx, but its headers are still
				// processed:
				// the listing listens for this event and refreshes itself.
				return ResponseEntity.noContent().header("HX-Trigger", "refreshEntries").build();
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
		return MediaType.parseMediaType(ArtifactContentType.of(artifactPath));
	}

}