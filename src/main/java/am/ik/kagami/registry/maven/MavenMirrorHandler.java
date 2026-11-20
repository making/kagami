package am.ik.kagami.registry.maven;

import am.ik.kagami.registry.Format;
import am.ik.kagami.registry.MirrorHandler;
import am.ik.kagami.registry.MirrorRequest;
import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Serves Maven repositories: the artifact is served from storage and fetched from the
 * upstream on a cache miss. This is the historical Kagami behavior, moved into a format
 * handler without changing the response.
 */
@Component
public class MavenMirrorHandler implements MirrorHandler {

	/**
	 * Content types served inline so that the browser renders them instead of forcing a
	 * file download: text-like files such as checksums, POMs, signatures and sigstore
	 * bundles.
	 */
	private static final Set<MediaType> INLINE_MEDIA_TYPES = Set.of(MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN,
			MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/pgp-signature"));

	private final StorageService storageService;

	private final RemoteRepositoryService remoteRepositoryService;

	public MavenMirrorHandler(StorageService storageService, RemoteRepositoryService remoteRepositoryService) {
		this.storageService = storageService;
		this.remoteRepositoryService = remoteRepositoryService;
	}

	@Override
	public Format format() {
		return Format.MAVEN;
	}

	@Override
	public ResponseEntity<Resource> handle(MirrorRequest request) throws IOException {
		ArtifactLocation location = new ArtifactLocation(request.repositoryId(), request.artifactPath());

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
			MediaType contentType = MediaType.parseMediaType(ArtifactContentType.of(request.artifactPath()));
			String disposition = INLINE_MEDIA_TYPES.contains(contentType) ? "inline" : "attachment";
			CacheControl cacheControl = CacheControl.maxAge(Duration.ofSeconds(31536000));
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(contentType);
			headers.setContentLength(resource.contentLength());
			headers.setCacheControl(
					request.repository().isPrivate() ? cacheControl.cachePrivate() : cacheControl.cachePublic());
			headers.set(HttpHeaders.CONTENT_DISPOSITION,
					"%s;filename=%s".formatted(disposition, resource.getFilename()));
			return new ResponseEntity<>(resource, headers, HttpStatus.OK);
		}

		return ResponseEntity.notFound().build();
	}

}
