package am.ik.kagami.registry;

import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Base for format handlers that only serve artifacts already present in storage.
 * <p>
 * It is the starting point for the npm and PyPI handlers until they grow their index
 * rewriting and upstream fetch behavior. Routing, path validation and response
 * construction are already the final ones, so an artifact stored at the request path is
 * served exactly as the Maven handler serves a cache hit.
 */
public abstract class StoredArtifactMirrorHandler implements MirrorHandler {

	private final StorageService storageService;

	protected StoredArtifactMirrorHandler(StorageService storageService) {
		this.storageService = storageService;
	}

	@Override
	public ResponseEntity<Resource> handle(MirrorRequest request) throws IOException {
		ArtifactLocation location = new ArtifactLocation(request.repositoryId(), request.artifactPath());
		Optional<Resource> stored = this.storageService.retrieve(location);
		if (stored.isEmpty() || !stored.get().exists()) {
			return ResponseEntity.notFound().build();
		}
		Resource resource = stored.get();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(ArtifactContentType.of(request.artifactPath(), resource)));
		headers.setContentLength(resource.contentLength());
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + location.name());
		return new ResponseEntity<>(resource, headers, HttpStatus.OK);
	}

}
