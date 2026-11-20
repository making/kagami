package am.ik.kagami.registry.npm;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rewrites the {@code dist.tarball} URLs of an npm document so that they point at this
 * Kagami instance.
 * <p>
 * An npm packument contains absolute upstream tarball URLs. Returning it verbatim would
 * make the client download the tarball from the upstream, bypassing the mirror. Only
 * {@code dist.tarball} is rewritten: {@code dist.shasum} and {@code dist.integrity} are
 * content hashes and stay valid. A single version manifest has the same structure at its
 * root, so the rewrite covers both the packument and the version form (including the
 * abridged {@code application/vnd.npm.install-v1+json} response, whose {@code versions}
 * map has the same shape).
 */
final class PackumentRewriter {

	private static final Logger logger = LoggerFactory.getLogger(PackumentRewriter.class);

	private final JsonMapper jsonMapper;

	PackumentRewriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Rewrite every {@code dist.tarball} in the document.
	 * @param body the upstream document
	 * @param baseUrl the externally visible base URL of this Kagami instance
	 * @param repositoryId the repository the document was served from
	 * @param packageName the package the document belongs to
	 * @return the rewritten document, or the original body when it is not a JSON object
	 */
	byte[] rewrite(byte[] body, String baseUrl, String repositoryId, String packageName) {
		JsonNode root;
		try {
			root = this.jsonMapper.readTree(body);
		}
		catch (JacksonException e) {
			logger.debug("Upstream npm document for {} is not valid JSON, serving it as is", packageName);
			return body;
		}
		if (!(root instanceof ObjectNode document)) {
			return body;
		}
		boolean changed = rewriteDist(document, baseUrl, repositoryId, packageName);
		if (document.get("versions") instanceof ObjectNode versions) {
			for (Map.Entry<String, JsonNode> entry : versions.properties()) {
				if (entry.getValue() instanceof ObjectNode version) {
					changed |= rewriteDist(version, baseUrl, repositoryId, packageName);
				}
			}
		}
		if (!changed) {
			return body;
		}
		try {
			return this.jsonMapper.writeValueAsBytes(document);
		}
		catch (JacksonException e) {
			// The tree was parsed from this body, so serialization cannot realistically
			// fail; serving the original body is still better than an error
			logger.debug("Failed to serialize the rewritten npm document for {}", packageName, e);
			return body;
		}
	}

	private static boolean rewriteDist(ObjectNode version, String baseUrl, String repositoryId, String packageName) {
		if (!(version.get("dist") instanceof ObjectNode dist)) {
			return false;
		}
		JsonNode tarball = dist.get("tarball");
		if (tarball == null || !tarball.isTextual()) {
			return false;
		}
		String fileName = tarballFileName(tarball.asText());
		if (fileName == null) {
			return false;
		}
		dist.put("tarball", mirrorUrl(baseUrl, repositoryId, packageName, fileName));
		return true;
	}

	private static String mirrorUrl(String baseUrl, String repositoryId, String packageName, String fileName) {
		String base = baseUrl;
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return "%s/artifacts/%s/%s/-/%s".formatted(base, repositoryId, packageName, fileName);
	}

	static @Nullable String tarballFileName(String tarball) {
		String path;
		try {
			path = URI.create(tarball).getPath();
		}
		catch (IllegalArgumentException e) {
			path = tarball;
		}
		if (path == null) {
			return null;
		}
		int lastSlash = path.lastIndexOf('/');
		String fileName = lastSlash < 0 ? path : path.substring(lastSlash + 1);
		return fileName.isEmpty() ? null : fileName;
	}

}
