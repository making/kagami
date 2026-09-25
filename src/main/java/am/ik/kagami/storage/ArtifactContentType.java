package am.ik.kagami.storage;

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
		return switch (extension(fileName)) {
			case "jar" -> "application/java-archive";
			case "pom", "xml" -> "application/xml";
			case "properties", "repositories", "sha1", "sha256", "sha512", "md5" -> "text/plain";
			case "asc" -> "application/pgp-signature";
			case "json" -> "application/json";
			default -> "application/octet-stream";
		};
	}

	private static String extension(String fileName) {
		int lastDot = fileName.lastIndexOf('.');
		return lastDot < 0 ? "" : fileName.substring(lastDot + 1);
	}

}
