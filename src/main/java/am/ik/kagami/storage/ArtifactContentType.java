package am.ik.kagami.storage;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Maps an artifact file name to the content type it is stored and served with.
 * <p>
 * Object storage keeps the content type with the object, so the backend has to decide it
 * at upload time rather than only when a request is served. Both decisions read from this
 * one table.
 */
public final class ArtifactContentType {

	private ArtifactContentType() {
	}

	/**
	 * The content type of the given file.
	 * @param path the file name, or any path ending with it
	 * @return the content type, {@code application/octet-stream} for unknown extensions
	 */
	public static String of(String path) {
		String fileName = path.substring(path.lastIndexOf('/') + 1);
		if (fileName.endsWith(".tar.gz")) {
			return "application/gzip";
		}
		return switch (extension(fileName)) {
			case "jar" -> "application/java-archive";
			case "pom", "xml" -> "application/xml";
			case "properties", "repositories", "sha1", "sha256", "sha512", "md5" -> "text/plain";
			case "asc" -> "application/pgp-signature";
			case "json" -> "application/json";
			case "tgz", "gz" -> "application/gzip";
			case "whl", "zip" -> "application/zip";
			default -> "application/octet-stream";
		};
	}

	/**
	 * The content type to serve the given resource with. A content type persisted with
	 * the stored object (for example the upstream Content-Type recorded by the S3
	 * backend) takes precedence; the extension table is only a fallback when it is absent
	 * or not a valid media type.
	 * @param path the artifact path, used for the extension fallback
	 * @param resource the stored resource
	 * @return the content type
	 */
	public static String of(String path, @Nullable Resource resource) {
		if (resource instanceof ContentTypeAware aware) {
			String stored = aware.getContentType();
			if (stored != null && !stored.isBlank()) {
				try {
					return MediaType.parseMediaType(stored).toString();
				}
				catch (InvalidMediaTypeException e) {
					// Fall back to the extension table below
				}
			}
		}
		return of(path);
	}

	private static String extension(String fileName) {
		int lastDot = fileName.lastIndexOf('.');
		return lastDot < 0 ? "" : fileName.substring(lastDot + 1);
	}

}
