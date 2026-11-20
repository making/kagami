package am.ik.kagami.registry.pypi;

import am.ik.kagami.KagamiProperties.Repository;
import am.ik.kagami.metadata.MetadataCache;
import am.ik.kagami.metadata.MetadataDocument;
import am.ik.kagami.metadata.MetadataUnavailableException;
import am.ik.kagami.registry.Format;
import am.ik.kagami.registry.MirrorHandler;
import am.ik.kagami.registry.MirrorRequest;
import am.ik.kagami.registry.RevalidationPolicy;
import am.ik.kagami.repository.ExpectedDigest;
import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.repository.RemoteRepositoryService.LinkedFetchOutcome;
import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
import org.springframework.util.StringUtils;

/**
 * Serves PyPI repositories: the simple index (PEP 503 HTML and PEP 691 JSON) and the
 * artifact files it links to.
 * <p>
 * Index documents are served through the {@link MetadataCache}; their links are rewritten
 * to this mirror so that pip, uv and other clients download through Kagami instead of
 * bypassing it. The file endpoints resolve the requested name against the upstream index,
 * fetch the link only when its host is allowlisted, verify the {@code #sha256=} fragment
 * the index advertises and then store the artifact like any other mirrored file.
 */
@Component
public class PypiMirrorHandler implements MirrorHandler {

	private static final Logger logger = LoggerFactory.getLogger(PypiMirrorHandler.class);

	private static final String SIMPLE = "simple";

	private static final String FILES = "files";

	private static final String JSON_ACCEPT = "application/vnd.pypi.simple.v1+json";

	/**
	 * Hosts a PyPI index may link to when the repository does not configure its own
	 * allowlist. The repository host is always allowed as well.
	 */
	private static final Set<String> DEFAULT_ALLOWED_HOSTS = Set.of("pypi.org", "files.pythonhosted.org");

	private static final Duration IMMUTABLE_MAX_AGE = Duration.ofSeconds(31536000);

	private final StorageService storageService;

	private final MetadataCache metadataCache;

	private final RemoteRepositoryService remoteRepositoryService;

	private final JsonMapper jsonMapper;

	public PypiMirrorHandler(StorageService storageService, MetadataCache metadataCache,
			RemoteRepositoryService remoteRepositoryService, JsonMapper jsonMapper) {
		this.storageService = storageService;
		this.metadataCache = metadataCache;
		this.remoteRepositoryService = remoteRepositoryService;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Format format() {
		return Format.PYPI;
	}

	/**
	 * A simple index changes whenever a release is published, so it is served through the
	 * metadata cache and revalidated. A wheel or sdist never changes once published.
	 */
	@Override
	public RevalidationPolicy revalidationPolicy(String artifactPath) {
		return artifactPath.startsWith(SIMPLE + "/") || artifactPath.equals(SIMPLE) ? RevalidationPolicy.MUTABLE_INDEX
				: RevalidationPolicy.IMMUTABLE;
	}

	@Override
	public ResponseEntity<Resource> handle(MirrorRequest request) throws IOException {
		ArtifactLocation location = new ArtifactLocation(request.repositoryId(), request.artifactPath());
		String[] segments = location.artifactPath().split("/");
		if (segments.length == 1 && SIMPLE.equals(segments[0])) {
			return handleIndex(request, null);
		}
		if (segments.length == 2 && SIMPLE.equals(segments[0])) {
			return handleIndex(request, PypiProjectName.normalize(segments[1]));
		}
		if (segments.length == 3 && FILES.equals(segments[0])) {
			String project = PypiProjectName.normalize(segments[1]);
			if (!StringUtils.hasText(segments[2])) {
				throw new IllegalArgumentException("Invalid PyPI file name: '" + segments[2] + "'");
			}
			return handleFile(request, project, segments[2]);
		}
		return ResponseEntity.notFound().build();
	}

	/**
	 * Serve the root index (the list of projects) or a project index. The upstream
	 * representation decides the response: when it answers JSON the JSON body is passed
	 * through with its links rewritten, otherwise HTML stays HTML.
	 */
	private ResponseEntity<Resource> handleIndex(MirrorRequest request, @Nullable String project) {
		String accept = acceptable(request.headers().getFirst(HttpHeaders.ACCEPT));
		String indexPath = project == null ? SIMPLE + "/" : SIMPLE + "/" + project + "/";
		Optional<MetadataDocument> document;
		try {
			document = this.metadataCache.get(request.repositoryId(), request.repository(), indexPath, accept);
		}
		catch (MetadataUnavailableException e) {
			logger.debug("PyPI index {} is unavailable: {}", indexPath, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		if (document.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		MetadataDocument index = document.get();
		MediaType upstreamType = index.contentType();
		boolean json = isJson(upstreamType, index.body());
		byte[] body;
		try {
			if (project == null) {
				// The root JSON carries project names only, so there is nothing to
				// rewrite
				body = json ? index.body()
						: SimpleIndexRewriter
							.rewriteProjectLinks(text(index, upstreamType), request.baseUrl(), request.repositoryId())
							.getBytes(charset(upstreamType));
			}
			else if (json) {
				body = SimpleIndexRewriter.rewriteFileLinksJson(index.body(), request.baseUrl(), request.repositoryId(),
						project, this.jsonMapper);
			}
			else {
				body = SimpleIndexRewriter
					.rewriteFileLinks(text(index, upstreamType), request.baseUrl(), request.repositoryId(), project)
					.getBytes(charset(upstreamType));
			}
		}
		catch (JacksonException e) {
			logger.debug("Failed to rewrite PyPI index {}: {}", indexPath, e.getMessage());
			return ResponseEntity.internalServerError().build();
		}

		String filename = project == null ? (json ? "index.json" : "index.html") : project + (json ? ".json" : ".html");
		CacheControl cacheControl = CacheControl.noCache();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(upstreamType != null ? upstreamType : defaultIndexType(json));
		headers.setContentLength(body.length);
		headers.setCacheControl(
				request.repository().isPrivate() ? cacheControl.cachePrivate() : cacheControl.cachePublic());
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + filename);
		headers.setETag(etag(body));
		return new ResponseEntity<>(new ByteArrayResource(body), headers, HttpStatus.OK);
	}

	/**
	 * Serve a file linked from a project index. A stored file is served without asking
	 * the upstream again; otherwise the upstream link is resolved from the index and
	 * downloaded into storage.
	 */
	private ResponseEntity<Resource> handleFile(MirrorRequest request, String project, String filename)
			throws IOException {
		ArtifactLocation location = new ArtifactLocation(request.repositoryId(),
				FILES + "/" + project + "/" + filename);
		Optional<Resource> stored = this.storageService.retrieve(location);
		if (stored.isPresent() && stored.get().exists()) {
			return storedResponse(request.repository(), location, stored.get());
		}

		String accept = acceptable(request.headers().getFirst(HttpHeaders.ACCEPT));
		Optional<MetadataDocument> document;
		try {
			document = this.metadataCache.get(request.repositoryId(), request.repository(),
					SIMPLE + "/" + project + "/", accept);
		}
		catch (MetadataUnavailableException e) {
			logger.debug("PyPI index for {} is unavailable: {}", project, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		if (document.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		Optional<LocatedLink> link = locate(document.get(), request.repository(), project, filename);
		if (link.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		URI upstream = link.get().url();
		if (!isAllowedHost(upstream, request.repository())) {
			logger.debug("PyPI link host is not allowlisted: {}", upstream);
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		LinkedFetchOutcome outcome = this.remoteRepositoryService.fetchLinkedArtifact(location, upstream,
				ExpectedDigest.sha256Hex(link.get().sha256()).orElse(null));
		return switch (outcome) {
			case STORED ->
				storedResponse(request.repository(), location, this.storageService.retrieve(location).orElseThrow());
			case NOT_FOUND -> ResponseEntity.notFound().build();
			case FAILED, HASH_MISMATCH -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		};
	}

	/**
	 * Resolve the requested file name to an absolute upstream URL. A PEP 658
	 * {@code .metadata} or PEP 714 {@code .provenance} file that is not listed explicitly
	 * is derived from the link of the file it belongs to.
	 */
	private Optional<LocatedLink> locate(MetadataDocument document, Repository repository, String project,
			String filename) {
		MediaType type = document.contentType();
		boolean json = isJson(type, document.body());
		List<SimpleIndexRewriter.FileLink> links;
		try {
			links = json ? SimpleIndexRewriter.fileLinksJson(document.body(), this.jsonMapper)
					: SimpleIndexRewriter.fileLinks(text(document, type));
		}
		catch (JacksonException e) {
			logger.debug("Failed to read the PyPI index for {}: {}", project, e.getMessage());
			return Optional.empty();
		}
		return links.stream()
			.filter(link -> link.filename().equals(filename))
			.findFirst()
			.flatMap(link -> resolve(repository, link.url(), link.sha256()))
			.or(() -> derivedLink(repository, links, filename));
	}

	private Optional<LocatedLink> derivedLink(Repository repository, List<SimpleIndexRewriter.FileLink> links,
			String filename) {
		String baseName = SimpleIndexRewriter.derivedBaseName(filename);
		if (baseName == null) {
			return Optional.empty();
		}
		String suffix = filename.substring(baseName.length());
		return links.stream()
			.filter(link -> link.filename().equals(baseName))
			.findFirst()
			.flatMap(link -> resolve(repository, link.url(), null))
			.map(base -> new LocatedLink(appendSuffix(base.url(), suffix), null));
	}

	private Optional<LocatedLink> resolve(Repository repository, String href, @Nullable String sha256) {
		try {
			URI base = URI.create(repository.url());
			return Optional.of(new LocatedLink(base.resolve(href), sha256));
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static URI appendSuffix(URI url, String suffix) {
		String value = url.toString();
		int fragment = value.indexOf('#');
		String withoutFragment = fragment < 0 ? value : value.substring(0, fragment);
		return URI.create(withoutFragment + suffix);
	}

	private static boolean isAllowedHost(URI url, Repository repository) {
		String host = url.getHost();
		if (host == null) {
			return false;
		}
		return allowedHosts(repository).contains(host.toLowerCase(Locale.ROOT));
	}

	private static Set<String> allowedHosts(Repository repository) {
		Set<String> hosts = new HashSet<>();
		List<String> configured = repository.allowedHosts();
		if (configured != null) {
			for (String host : configured) {
				if (StringUtils.hasText(host)) {
					hosts.add(host.trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		String repositoryHost = repositoryHost(repository.url());
		if (repositoryHost != null) {
			hosts.add(repositoryHost);
		}
		if (configured == null || configured.isEmpty()) {
			hosts.addAll(DEFAULT_ALLOWED_HOSTS);
		}
		return hosts;
	}

	private static @Nullable String repositoryHost(String repositoryUrl) {
		try {
			String host = URI.create(repositoryUrl).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private ResponseEntity<Resource> storedResponse(Repository repository, ArtifactLocation location, Resource resource)
			throws IOException {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(ArtifactContentType.of(location.artifactPath(), resource)));
		headers.setContentLength(resource.contentLength());
		CacheControl cacheControl = CacheControl.maxAge(IMMUTABLE_MAX_AGE);
		headers.setCacheControl(repository.isPrivate() ? cacheControl.cachePrivate() : cacheControl.cachePublic());
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + location.name());
		return new ResponseEntity<>(resource, headers, HttpStatus.OK);
	}

	/**
	 * A link resolved against the repository URL.
	 *
	 * @param url the absolute upstream URL
	 * @param sha256 the expected SHA-256, or {@code null}
	 */
	private record LocatedLink(URI url, @Nullable String sha256) {
	}

	/**
	 * A client that sends no preference, or {@code *&#47;*}, is treated like a request
	 * without {@code Accept} so that both share one cached representation.
	 */
	private static @Nullable String acceptable(@Nullable String accept) {
		if (!StringUtils.hasText(accept) || "*/*".equals(accept.trim())) {
			return null;
		}
		return accept;
	}

	private static boolean isJson(@Nullable MediaType type, byte[] body) {
		if (type != null && type.getSubtype().toLowerCase(Locale.ROOT).contains("json")) {
			return true;
		}
		int start = 0;
		while (start < body.length && Character.isWhitespace(body[start])) {
			start++;
		}
		return start < body.length && body[start] == '{';
	}

	private static MediaType defaultIndexType(boolean json) {
		return json ? MediaType.parseMediaType(JSON_ACCEPT) : MediaType.TEXT_HTML;
	}

	private static Charset charset(@Nullable MediaType type) {
		return type != null && type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
	}

	private static String text(MetadataDocument document, @Nullable MediaType type) {
		return new String(document.body(), charset(type));
	}

	private static String etag(byte[] body) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
			return "\"" + HexFormat.of().formatHex(digest) + "\"";
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

}
