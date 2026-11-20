package am.ik.kagami.registry.npm;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.metadata.MetadataCache;
import am.ik.kagami.metadata.MetadataDocument;
import am.ik.kagami.metadata.MetadataUnavailableException;
import am.ik.kagami.registry.Format;
import am.ik.kagami.registry.MirrorHandler;
import am.ik.kagami.registry.MirrorRequest;
import am.ik.kagami.registry.RevalidationPolicy;
import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.repository.RemoteRepositoryService.FetchOutcome;
import am.ik.kagami.repository.UpstreamAuthentication;
import am.ik.kagami.storage.ArtifactContentType;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves an npm registry mirror.
 * <p>
 * The npm client first fetches the packument and then follows {@code dist.tarball}. A
 * packument returned verbatim would point the client at the upstream and bypass the
 * mirror, so {@link PackumentRewriter} points every tarball URL at this instance.
 * Packuments and version manifests are mutable indexes served through the
 * {@link MetadataCache}; tarballs are immutable and stored in artifact storage. Registry
 * endpoints that have no artifact ({@code /-/ping}, search, audit) are answered locally
 * or proxied without storing.
 * <p>
 * Tarballs are fetched from the URL the packument names, not from a path guessed from the
 * request, so the packument stays the single source of truth. The URL host is checked
 * against {@code kagami.upstream.tarball-hosts} (plus the repository host) before the
 * fetch, and the tarball is stored only under the requested package path.
 */
@Component
public class NpmMirrorHandler implements MirrorHandler {

	private static final Logger logger = LoggerFactory.getLogger(NpmMirrorHandler.class);

	private static final String SEARCH_PATH = "-/v1/search";

	private static final String AUDIT_PATH = "-/npm/v1/security/audits/quick";

	private static final Duration IMMUTABLE_MAX_AGE = Duration.ofSeconds(31536000);

	private final StorageService storageService;

	private final MetadataCache metadataCache;

	private final RemoteRepositoryService remoteRepositoryService;

	private final KagamiProperties properties;

	private final JsonMapper jsonMapper;

	private final PackumentRewriter rewriter;

	private final RestClient restClient;

	public NpmMirrorHandler(StorageService storageService, MetadataCache metadataCache,
			RemoteRepositoryService remoteRepositoryService, KagamiProperties properties, JsonMapper jsonMapper,
			RestClient.Builder restClientBuilder, ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder) {
		this.storageService = storageService;
		this.metadataCache = metadataCache;
		this.remoteRepositoryService = remoteRepositoryService;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.rewriter = new PackumentRewriter(jsonMapper);
		KagamiProperties.UpstreamConfig upstream = properties.upstream();
		HttpClientSettings settings = HttpClientSettings.defaults()
			.withConnectTimeout(upstream.connectTimeout())
			.withReadTimeout(upstream.requestTimeout());
		this.restClient = restClientBuilder.requestFactory(requestFactoryBuilder.build(settings)).build();
	}

	@Override
	public Format format() {
		return Format.NPM;
	}

	/**
	 * The packument and version manifests are mutable; a tarball is immutable.
	 */
	@Override
	public RevalidationPolicy revalidationPolicy(String artifactPath) {
		NpmRequestPath path;
		try {
			path = NpmRequestPath.parse(artifactPath);
		}
		catch (IllegalArgumentException e) {
			return RevalidationPolicy.IMMUTABLE;
		}
		return switch (path) {
			case NpmRequestPath.Packument packument -> RevalidationPolicy.MUTABLE_INDEX;
			case NpmRequestPath.Version version -> RevalidationPolicy.MUTABLE_INDEX;
			default -> RevalidationPolicy.IMMUTABLE;
		};
	}

	@Override
	public ResponseEntity<Resource> handle(MirrorRequest request) throws IOException {
		NpmRequestPath path = NpmRequestPath.parse(request.artifactPath());
		return switch (path) {
			case NpmRequestPath.Ping ping -> ping();
			case NpmRequestPath.Search search -> proxy(request, HttpMethod.GET, SEARCH_PATH);
			case NpmRequestPath.Audit audit -> proxy(request, HttpMethod.POST, AUDIT_PATH);
			case NpmRequestPath.Packument packument -> packument(request, packument);
			case NpmRequestPath.Version version -> versionManifest(request, version);
			case NpmRequestPath.Tarball tarball -> tarball(request, tarball);
		};
	}

	/**
	 * npm clients ping the registry before installing. The mirror is alive whenever it
	 * can answer, so the check is local and never reaches the upstream.
	 */
	private ResponseEntity<Resource> ping() {
		return jsonResponse("{}", null);
	}

	/**
	 * Serve the packument through the metadata cache and rewrite its tarball URLs.
	 */
	private ResponseEntity<Resource> packument(MirrorRequest request, NpmRequestPath.Packument packument) {
		Optional<MetadataDocument> document;
		try {
			document = this.metadataCache.get(request.repositoryId(), request.repository(), packument.packageName());
		}
		catch (MetadataUnavailableException e) {
			logger.debug("Packument {} is unavailable: {}", packument.packageName(), e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		return document.map(doc -> metadataResponse(request, doc, packument.packageName(), packument.packageName()))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Serve a single version manifest. The upstream endpoint is used directly so that a
	 * client asking for one version does not download the whole packument.
	 */
	private ResponseEntity<Resource> versionManifest(MirrorRequest request, NpmRequestPath.Version version) {
		String indexPath = version.packageName() + "/" + version.version();
		Optional<MetadataDocument> document;
		try {
			document = this.metadataCache.get(request.repositoryId(), request.repository(), indexPath);
		}
		catch (MetadataUnavailableException e) {
			logger.debug("Version manifest {} is unavailable: {}", indexPath, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		return document.map(doc -> metadataResponse(request, doc, indexPath, version.packageName()))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Build the response of a rewritten metadata document.
	 */
	private ResponseEntity<Resource> metadataResponse(MirrorRequest request, MetadataDocument document,
			String indexPath, String packageName) {
		byte[] body = this.rewriter.rewrite(document.body(), request.baseUrl(), request.repositoryId(), packageName);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(contentType(document));
		headers.setContentLength(body.length);
		headers.setCacheControl(cacheControl(false, request));
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + fileName(indexPath) + ".json");
		if (document.etag() != null) {
			headers.setETag(document.etag());
		}
		return new ResponseEntity<>(new ByteArrayResource(body), headers, HttpStatus.OK);
	}

	private static MediaType contentType(MetadataDocument document) {
		MediaType contentType = document.contentType();
		return contentType != null ? contentType : MediaType.APPLICATION_JSON;
	}

	/**
	 * Serve a tarball: from storage on a hit, otherwise from the URL the packument names.
	 */
	private ResponseEntity<Resource> tarball(MirrorRequest request, NpmRequestPath.Tarball tarball) throws IOException {
		ArtifactLocation location = new ArtifactLocation(request.repositoryId(), request.artifactPath());
		Optional<Resource> stored = this.storageService.retrieve(location);
		if (stored.isPresent() && stored.get().exists()) {
			return storedResponse(request, tarball.fileName(), stored.get());
		}

		Optional<MetadataDocument> packument;
		try {
			packument = this.metadataCache.get(request.repositoryId(), request.repository(), tarball.packageName());
		}
		catch (MetadataUnavailableException e) {
			logger.debug("Packument {} is unavailable while resolving {}: {}", tarball.packageName(),
					tarball.fileName(), e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		if (packument.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		Optional<String> tarballUrl = findTarball(packument.get().body(), tarball.fileName());
		if (tarballUrl.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		URI url = URI.create(tarballUrl.get());
		if (!isAllowedHost(url, request.repository())) {
			logger.debug("Refusing to fetch {} from {}: host is not in the tarball allowlist", tarball.fileName(), url);
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
		FetchOutcome outcome = this.remoteRepositoryService.fetchFromUpstream(url, location);
		return switch (outcome) {
			case STORED -> {
				Optional<Resource> fetched = this.storageService.retrieve(location);
				yield fetched.isPresent() && fetched.get().exists()
						? storedResponse(request, tarball.fileName(), fetched.get())
						: ResponseEntity.internalServerError().build();
			}
			case NOT_FOUND -> ResponseEntity.notFound().build();
			case FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		};
	}

	private ResponseEntity<Resource> storedResponse(MirrorRequest request, String fileName, Resource resource)
			throws IOException {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(ArtifactContentType.of(request.artifactPath(), resource)));
		headers.setContentLength(resource.contentLength());
		headers.setCacheControl(cacheControl(true, request));
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + fileName);
		return new ResponseEntity<>(resource, headers, HttpStatus.OK);
	}

	/**
	 * Find the tarball with the requested file name in the packument. The packument is
	 * the source of the URL, so a tarball cannot be fetched from a package it does not
	 * belong to.
	 */
	private Optional<String> findTarball(byte[] packument, String fileName) {
		JsonNode root;
		try {
			root = this.jsonMapper.readTree(packument);
		}
		catch (JacksonException e) {
			logger.debug("Cannot resolve {} in an invalid packument", fileName);
			return Optional.empty();
		}
		JsonNode versions = root.get("versions");
		if (versions == null || !versions.isObject()) {
			return Optional.empty();
		}
		for (Map.Entry<String, JsonNode> entry : versions.properties()) {
			JsonNode dist = entry.getValue().get("dist");
			if (dist == null || !dist.isObject()) {
				continue;
			}
			JsonNode tarball = dist.get("tarball");
			if (tarball != null && tarball.isTextual()
					&& fileName.equals(PackumentRewriter.tarballFileName(tarball.asText()))) {
				return Optional.of(tarball.asText());
			}
		}
		return Optional.empty();
	}

	/**
	 * The tarball host allowlist: the hosts configured in
	 * {@code kagami.upstream.tarball-hosts} plus the host of the repository URL, which is
	 * trusted by definition. An empty allowlist therefore only accepts tarballs served by
	 * the registry itself.
	 */
	private boolean isAllowedHost(URI url, KagamiProperties.Repository repository) {
		String scheme = url.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			return false;
		}
		String host = url.getHost();
		if (!StringUtils.hasText(host)) {
			return false;
		}
		Set<String> allowedHosts = this.properties.upstream()
			.tarballHosts()
			.stream()
			.filter(StringUtils::hasText)
			.map(candidate -> candidate.toLowerCase(Locale.ROOT))
			.collect(Collectors.toSet());
		String repositoryHost = repositoryHost(repository.url());
		if (repositoryHost != null) {
			allowedHosts.add(repositoryHost);
		}
		return allowedHosts.contains(host.toLowerCase(Locale.ROOT));
	}

	private static @Nullable String repositoryHost(String repositoryUrl) {
		try {
			String host = URI.create(repositoryUrl.trim()).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Proxy a registry endpoint that is neither stored nor cached. The upstream status is
	 * propagated so that a registry without an audit API keeps answering {@code 404}.
	 */
	private ResponseEntity<Resource> proxy(MirrorRequest request, HttpMethod method, String upstreamPath) {
		URI url = upstreamUrl(request, upstreamPath);
		byte[] body = request.body();
		try {
			RestClient.RequestBodySpec specification = this.restClient.method(method).uri(url).headers(headers -> {
				UpstreamAuthentication.apply(headers, request.repository());
				headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
				copyHeader(request.headers(), headers, HttpHeaders.ACCEPT);
				copyHeader(request.headers(), headers, HttpHeaders.CONTENT_TYPE);
			});
			if (body.length > 0) {
				specification.body(body);
			}
			ResponseEntity<byte[]> upstream = specification.exchange((clientRequest, clientResponse) -> {
				byte[] responseBody = readBody(clientResponse.getBody());
				HttpHeaders responseHeaders = new HttpHeaders();
				copyHeader(clientResponse.getHeaders(), responseHeaders, HttpHeaders.CONTENT_TYPE);
				return new ResponseEntity<>(responseBody, responseHeaders, clientResponse.getStatusCode());
			});
			byte[] responseBody = upstream.getBody() == null ? new byte[0] : upstream.getBody();
			if (responseBody.length == 0) {
				return new ResponseEntity<>(upstream.getHeaders(), upstream.getStatusCode());
			}
			return new ResponseEntity<>(new ByteArrayResource(responseBody), upstream.getHeaders(),
					upstream.getStatusCode());
		}
		catch (RestClientException e) {
			logger.debug("Failed to proxy {}: {}", url, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
	}

	private static byte[] readBody(@Nullable InputStream body) {
		if (body == null) {
			return new byte[0];
		}
		try {
			return body.readAllBytes();
		}
		catch (IOException e) {
			throw new RestClientException("Failed to read the upstream response body", e);
		}
	}

	private static void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
		String value = source.getFirst(name);
		if (StringUtils.hasText(value)) {
			target.set(name, value);
		}
	}

	private URI upstreamUrl(MirrorRequest request, String upstreamPath) {
		String base = StringUtils.hasText(request.repository().url()) ? request.repository().url().trim() : "";
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		String query = request.queryString();
		String url = base + "/" + upstreamPath + (query != null ? "?" + query : "");
		return URI.create(url);
	}

	private ResponseEntity<Resource> jsonResponse(String body, @Nullable String etag) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setContentLength(bytes.length);
		headers.setCacheControl(CacheControl.noCache());
		if (etag != null) {
			headers.setETag(etag);
		}
		return new ResponseEntity<>(new ByteArrayResource(bytes), headers, HttpStatus.OK);
	}

	private static CacheControl cacheControl(boolean immutable, MirrorRequest request) {
		CacheControl cacheControl = immutable ? CacheControl.maxAge(IMMUTABLE_MAX_AGE) : CacheControl.noCache();
		return request.repository().isPrivate() ? cacheControl.cachePrivate() : cacheControl.cachePublic();
	}

	private static String fileName(String path) {
		int lastSlash = path.lastIndexOf('/');
		return lastSlash < 0 ? path : path.substring(lastSlash + 1);
	}

}
