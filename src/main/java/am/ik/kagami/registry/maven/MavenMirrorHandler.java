package am.ik.kagami.registry.maven;

import am.ik.kagami.metadata.MetadataCache;
import am.ik.kagami.metadata.MetadataDocument;
import am.ik.kagami.metadata.MetadataUnavailableException;
import am.ik.kagami.registry.Format;
import am.ik.kagami.registry.MirrorHandler;
import am.ik.kagami.registry.MirrorRequest;
import am.ik.kagami.registry.RevalidationPolicy;
import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
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

	private static final Logger logger = LoggerFactory.getLogger(MavenMirrorHandler.class);

	/**
	 * Content types served inline so that the browser renders them instead of forcing a
	 * file download: text-like files such as checksums, POMs, signatures and sigstore
	 * bundles.
	 */
	private static final Set<MediaType> INLINE_MEDIA_TYPES = Set.of(MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN,
			MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/pgp-signature"));

	private final StorageService storageService;

	private final RemoteRepositoryService remoteRepositoryService;

	private final MetadataCache metadataCache;

	public MavenMirrorHandler(StorageService storageService, RemoteRepositoryService remoteRepositoryService,
			MetadataCache metadataCache) {
		this.storageService = storageService;
		this.remoteRepositoryService = remoteRepositoryService;
		this.metadataCache = metadataCache;
	}

	@Override
	public Format format() {
		return Format.MAVEN;
	}

	/**
	 * Maven metadata is mutable: the versions, the snapshot timestamps and the
	 * {@code latest} / {@code release} values change upstream long after the artifact was
	 * first mirrored. Its checksums are mutable for the same reason, and a stale checksum
	 * would make a client reject the fresh metadata.
	 */
	@Override
	public RevalidationPolicy revalidationPolicy(String artifactPath) {
		return isMutableIndex(artifactPath) ? RevalidationPolicy.MUTABLE_INDEX : RevalidationPolicy.IMMUTABLE;
	}

	private static boolean isMutableIndex(String artifactPath) {
		int lastSlash = artifactPath.lastIndexOf('/');
		String name = lastSlash < 0 ? artifactPath : artifactPath.substring(lastSlash + 1);
		if (!name.startsWith("maven-metadata")) {
			return false;
		}
		return name.endsWith(".xml") || name.endsWith(".xml.sha1") || name.endsWith(".xml.md5")
				|| name.endsWith(".xml.sha256") || name.endsWith(".xml.sha512");
	}

	@Override
	public ResponseEntity<Resource> handle(MirrorRequest request) throws IOException {
		ArtifactLocation location = new ArtifactLocation(request.repositoryId(), request.artifactPath());

		if (revalidationPolicy(request.artifactPath()) == RevalidationPolicy.MUTABLE_INDEX) {
			return handleMutableIndex(request, location);
		}

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
			MediaType contentType = MediaType.parseMediaType(ArtifactContentType.of(request.artifactPath(), resource));
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

	/**
	 * Serve a mutable index through the metadata cache. The cache owns the upstream
	 * revalidation and stale-serving policy; this method only maps its outcome to a
	 * response. Mutable indexes do not pass through the artifact storage, so they stay
	 * out of the browse listing.
	 */
	private ResponseEntity<Resource> handleMutableIndex(MirrorRequest request, ArtifactLocation location) {
		Optional<MetadataDocument> document;
		try {
			document = this.metadataCache.get(request.repositoryId(), request.repository(), request.artifactPath());
		}
		catch (MetadataUnavailableException e) {
			logger.debug("Index {} is unavailable: {}", request.artifactPath(), e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		if (document.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		MetadataDocument index = document.get();
		// Maven clients rely on the extension based Content-Type; the upstream value is
		// kept in the document for the formats whose indexes need it
		MediaType contentType = MediaType.parseMediaType(ArtifactContentType.of(request.artifactPath()));
		// Clients must revalidate: the index may change upstream at any time
		CacheControl cacheControl = CacheControl.noCache();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(contentType);
		headers.setContentLength(index.contentLength());
		headers.setCacheControl(
				request.repository().isPrivate() ? cacheControl.cachePrivate() : cacheControl.cachePublic());
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + location.name());
		if (index.etag() != null) {
			headers.setETag(index.etag());
		}
		return new ResponseEntity<>(new ByteArrayResource(index.body()), headers, HttpStatus.OK);
	}

}
