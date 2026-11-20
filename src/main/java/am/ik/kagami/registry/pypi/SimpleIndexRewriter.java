package am.ik.kagami.registry.pypi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriUtils;

/**
 * Rewrites the links of a PyPI simple index so that a client downloads through Kagami
 * instead of contacting the upstream directly.
 * <p>
 * Both index representations are handled: PEP 503 HTML with its {@code <a href>} file
 * links and PEP 691 JSON with its {@code files[*].url} values. Everything except the URL
 * is left untouched, so hash fragments, {@code data-requires-python},
 * {@code data-dist-info-metadata}, PEP 658 {@code .metadata} links, PEP 714
 * {@code .provenance} links and unknown JSON fields such as {@code versions} and
 * {@code meta} survive the rewrite.
 */
final class SimpleIndexRewriter {

	private static final Pattern ANCHOR_HREF = Pattern.compile("(<a\\s[^>]*?\\bhref\\s*=\\s*)([\"'])([^\"']*)\\2",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern SHA256_FRAGMENT = Pattern.compile("(?:^|&)sha256=([0-9a-fA-F]{64})(?:&|$)");

	/** The file suffixes whose link is derived from a base file link (PEP 658/714). */
	private static final List<String> DERIVED_FILE_SUFFIXES = List.of(".metadata", ".provenance");

	private SimpleIndexRewriter() {
	}

	/**
	 * A downloadable file advertised by an index.
	 *
	 * @param filename the last path segment of the link
	 * @param url the link as it appears in the index, possibly relative
	 * @param sha256 the expected SHA-256 in hex, or {@code null} if the index gives none
	 */
	record FileLink(String filename, String url, @Nullable String sha256) {
	}

	/**
	 * Rewrite the project links of the root index to point at this mirror.
	 * @param html the upstream root index
	 * @param baseUrl the base URL of this Kagami instance
	 * @param repositoryId the repository identifier
	 * @return the rewritten document
	 */
	static String rewriteProjectLinks(String html, String baseUrl, String repositoryId) {
		return rewriteAnchors(html, href -> {
			URI uri = parse(href);
			if (uri == null) {
				return null;
			}
			String name = lastPathSegment(uri.getPath());
			if (name == null) {
				return null;
			}
			try {
				return simpleUrl(baseUrl, repositoryId, PypiProjectName.normalize(name));
			}
			catch (IllegalArgumentException e) {
				return null;
			}
		});
	}

	/**
	 * Rewrite the file links of a project index to point at this mirror.
	 * @param html the upstream project index
	 * @param baseUrl the base URL of this Kagami instance
	 * @param repositoryId the repository identifier
	 * @param project the normalized project name
	 * @return the rewritten document
	 */
	static String rewriteFileLinks(String html, String baseUrl, String repositoryId, String project) {
		return rewriteAnchors(html, href -> {
			URI uri = parse(href);
			if (uri == null) {
				return null;
			}
			String filename = lastPathSegment(uri.getPath());
			if (filename == null) {
				return null;
			}
			return fileUrl(baseUrl, repositoryId, project, filename, uri.getRawFragment());
		});
	}

	/**
	 * Rewrite {@code files[*].url} in a PEP 691 project index. Unknown fields are kept as
	 * they are.
	 * @param body the upstream JSON document
	 * @param baseUrl the base URL of this Kagami instance
	 * @param repositoryId the repository identifier
	 * @param project the normalized project name
	 * @param jsonMapper the JSON mapper
	 * @return the rewritten document
	 */
	static byte[] rewriteFileLinksJson(byte[] body, String baseUrl, String repositoryId, String project,
			JsonMapper jsonMapper) {
		JsonNode root = jsonMapper.readTree(body);
		if (root instanceof ObjectNode index && index.get("files") instanceof ArrayNode files) {
			for (JsonNode file : files) {
				if (file instanceof ObjectNode entry && entry.get("url") != null && entry.get("url").isTextual()) {
					URI uri = parse(entry.get("url").asText());
					if (uri == null) {
						continue;
					}
					String filename = lastPathSegment(uri.getPath());
					if (filename != null) {
						entry.put("url", fileUrl(baseUrl, repositoryId, project, filename, uri.getRawFragment()));
					}
				}
			}
		}
		return jsonMapper.writeValueAsBytes(root);
	}

	/**
	 * Extract the file links of an HTML project index.
	 * @param html the upstream project index
	 * @return the links, in document order
	 */
	static List<FileLink> fileLinks(String html) {
		List<FileLink> links = new ArrayList<>();
		Matcher matcher = ANCHOR_HREF.matcher(html);
		while (matcher.find()) {
			String href = matcher.group(3);
			URI uri = parse(href);
			if (uri == null) {
				continue;
			}
			String filename = lastPathSegment(uri.getPath());
			if (filename != null) {
				links.add(new FileLink(filename, href, sha256(uri.getRawFragment())));
			}
		}
		return links;
	}

	/**
	 * Extract the file links of a PEP 691 project index.
	 * @param body the upstream JSON document
	 * @param jsonMapper the JSON mapper
	 * @return the links, in document order
	 */
	static List<FileLink> fileLinksJson(byte[] body, JsonMapper jsonMapper) {
		List<FileLink> links = new ArrayList<>();
		JsonNode root = jsonMapper.readTree(body);
		if (!(root instanceof ObjectNode index) || !(index.get("files") instanceof ArrayNode files)) {
			return links;
		}
		for (JsonNode file : files) {
			if (!(file instanceof ObjectNode entry) || !entry.path("url").isTextual()) {
				continue;
			}
			String url = entry.get("url").asText();
			URI uri = parse(url);
			String filename = entry.path("filename").isTextual() ? entry.get("filename").asText()
					: (uri == null ? null : lastPathSegment(uri.getPath()));
			if (filename == null) {
				continue;
			}
			String hash = uri == null ? null : sha256(uri.getRawFragment());
			JsonNode hashes = entry.get("hashes");
			if (hash == null && hashes != null && hashes.path("sha256").isTextual()) {
				hash = hashes.get("sha256").asText().toLowerCase(Locale.ROOT);
			}
			links.add(new FileLink(filename, url, hash));
		}
		return links;
	}

	/**
	 * The base file name whose derived link a PEP 658 {@code .metadata} or PEP 714
	 * {@code .provenance} file belongs to.
	 * @param filename the requested file name
	 * @return the base file name, or {@code null} if the name carries no derived suffix
	 */
	static @Nullable String derivedBaseName(String filename) {
		for (String suffix : DERIVED_FILE_SUFFIXES) {
			if (filename.endsWith(suffix) && filename.length() > suffix.length()) {
				return filename.substring(0, filename.length() - suffix.length());
			}
		}
		return null;
	}

	/**
	 * The expected SHA-256 of a link fragment such as {@code #sha256=...}.
	 * @param fragment the raw fragment, or {@code null}
	 * @return the hash in hex, or {@code null} if the fragment carries no SHA-256
	 */
	static @Nullable String sha256(@Nullable String fragment) {
		if (fragment == null) {
			return null;
		}
		Matcher matcher = SHA256_FRAGMENT.matcher(fragment);
		return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
	}

	private static String rewriteAnchors(String html, Function<String, @Nullable String> rewrite) {
		Matcher matcher = ANCHOR_HREF.matcher(html);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String href = matcher.group(3);
			String replacement;
			try {
				replacement = rewrite.apply(href);
			}
			catch (RuntimeException e) {
				replacement = null;
			}
			String url = replacement == null ? href : replacement;
			matcher.appendReplacement(result,
					Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + url + matcher.group(2)));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private static String simpleUrl(String baseUrl, String repositoryId, String project) {
		return trimTrailingSlash(baseUrl) + "/artifacts/" + encode(repositoryId) + "/simple/" + encode(project) + "/";
	}

	private static String fileUrl(String baseUrl, String repositoryId, String project, String filename,
			@Nullable String fragment) {
		String url = trimTrailingSlash(baseUrl) + "/artifacts/" + encode(repositoryId) + "/files/" + encode(project)
				+ "/" + encode(filename);
		return fragment == null || fragment.isEmpty() ? url : url + "#" + fragment;
	}

	private static String encode(String segment) {
		return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
	}

	private static String trimTrailingSlash(String value) {
		String result = value;
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

	private static @Nullable URI parse(String value) {
		try {
			return URI.create(value);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * The last non-empty path segment, URL-decoded. A trailing slash from a directory
	 * link is ignored.
	 */
	private static @Nullable String lastPathSegment(@Nullable String path) {
		if (path == null) {
			return null;
		}
		String candidate = path;
		while (candidate.endsWith("/")) {
			candidate = candidate.substring(0, candidate.length() - 1);
		}
		int lastSlash = candidate.lastIndexOf('/');
		String name = lastSlash < 0 ? candidate : candidate.substring(lastSlash + 1);
		return name.isEmpty() ? null : name;
	}

}
