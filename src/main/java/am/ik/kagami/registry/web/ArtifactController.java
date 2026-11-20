package am.ik.kagami.registry.web;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.Repository;
import am.ik.kagami.registry.MirrorHandler;
import am.ik.kagami.registry.MirrorHandlerRegistry;
import am.ik.kagami.registry.MirrorRequest;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes mirror requests to the {@link MirrorHandler} of the repository format.
 * <p>
 * Every format shares the {@code /artifacts/{repositoryId}/**} namespace, so the
 * repository-bound token claim, the private/public rules, garbage collection, statistics
 * and browsing stay format-agnostic. The format only selects the handler; deleting is
 * format-agnostic and stays here.
 */
@RestController
@RequestMapping("/artifacts")
public class ArtifactController {

	private final StorageService storageService;

	private final Map<String, Repository> repositories;

	private final MirrorHandlerRegistry mirrorHandlers;

	public ArtifactController(StorageService storageService, MirrorHandlerRegistry mirrorHandlers,
			KagamiProperties properties) {
		this.storageService = storageService;
		this.mirrorHandlers = mirrorHandlers;
		this.repositories = properties.repositories();
	}

	@GetMapping("/{repositoryId}/**")
	public ResponseEntity<Resource> getArtifact(@PathVariable String repositoryId, HttpServletRequest request) {
		Repository repository = configuredRepository(repositoryId);
		if (repository == null) {
			return ResponseEntity.notFound().build();
		}
		MirrorHandler handler = this.mirrorHandlers.find(repositoryId).orElse(null);
		if (handler == null) {
			return ResponseEntity.notFound().build();
		}
		MirrorRequest mirrorRequest = MirrorRequest.builder()
			.repositoryId(repositoryId)
			.repository(repository)
			.artifactPath(extractArtifactPath(request, repositoryId))
			.headers(copyHeaders(request))
			.build();
		try {
			return handler.handle(mirrorRequest);
		}
		catch (IllegalArgumentException e) {
			// Rejected by the common ArtifactLocation path validation
			return ResponseEntity.badRequest().build();
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	@DeleteMapping("/{repositoryId}/**")
	public ResponseEntity<Void> deleteArtifact(@PathVariable String repositoryId, HttpServletRequest request) {
		if (configuredRepository(repositoryId) == null) {
			return ResponseEntity.notFound().build();
		}
		String artifactPath = extractArtifactPath(request, repositoryId);
		try {
			boolean deleted = this.storageService.delete(new ArtifactLocation(repositoryId, artifactPath));
			if (deleted) {
				// 204 is a "no swap" status for htmx, but its headers are still
				// processed: the listing listens for this event and refreshes itself.
				return ResponseEntity.noContent().header("HX-Trigger", "refreshEntries").build();
			}
			return ResponseEntity.notFound().build();
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().build();
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	private @Nullable Repository configuredRepository(String repositoryId) {
		Repository repository = this.repositories.get(repositoryId);
		// A repository without an upstream URL is not served, matching the previous
		// behavior of the Maven-only implementation
		return repository != null && repository.url() != null ? repository : null;
	}

	/**
	 * Extract the artifact path from the request URI and URL-decode it, so that an
	 * encoded slash in a scoped npm package ({@code @scope%2Fname}) reaches the handler
	 * as {@code @scope/name}. The result is still unvalidated; the handler passes it
	 * through {@link ArtifactLocation}.
	 */
	private static String extractArtifactPath(HttpServletRequest request, String repositoryId) {
		String fullPath = request.getRequestURI();
		// Remove the leading "/artifacts/" and repository ID to get the artifact path
		String prefix = "/artifacts/" + repositoryId + "/";
		String encodedPath = fullPath.startsWith(prefix) ? fullPath.substring(prefix.length())
				// Fallback - this shouldn't happen with proper routing
				: fullPath.substring(fullPath.indexOf(repositoryId) + repositoryId.length() + 1);
		return StringUtils.uriDecode(encodedPath, StandardCharsets.UTF_8);
	}

	private static HttpHeaders copyHeaders(HttpServletRequest request) {
		HttpHeaders headers = new HttpHeaders();
		Enumeration<String> names = request.getHeaderNames();
		if (names != null) {
			for (String name : Collections.list(names)) {
				headers.addAll(name, Collections.list(request.getHeaders(name)));
			}
		}
		return headers;
	}

}
