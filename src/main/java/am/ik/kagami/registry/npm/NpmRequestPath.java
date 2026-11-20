package am.ik.kagami.registry.npm;

/**
 * A request path addressed to an npm repository, classified into the kind of document the
 * npm client asked for.
 * <p>
 * The npm client addresses a packument as {@code /{package}}, a single version manifest
 * as {@code /{package}/{version}} and a tarball as {@code /{package}/-/{file}}. A scoped
 * package is either {@code @scope%2Fname} or {@code @scope/name}; the router decodes the
 * encoded form before this class sees it, so both arrive as {@code @scope/name}. The
 * registry-level endpoints ({@code /-/ping}, search and audit) are addressed under the
 * same repository prefix.
 */
sealed interface NpmRequestPath {

	/** {@code GET /-/ping}: liveness check served locally. */
	record Ping() implements NpmRequestPath {
	}

	/** {@code GET /-/v1/search}: search, proxied to the upstream without storing. */
	record Search() implements NpmRequestPath {
	}

	/** {@code POST /-/npm/v1/security/audits/quick}: audit, proxied without storing. */
	record Audit() implements NpmRequestPath {
	}

	/**
	 * {@code GET /{package}}: the packument, which lists every version.
	 *
	 * @param packageName the package name, {@code @scope/name} for a scoped package
	 */
	record Packument(String packageName) implements NpmRequestPath {
	}

	/**
	 * {@code GET /{package}/{version}}: a single version manifest.
	 *
	 * @param packageName the package name, {@code @scope/name} for a scoped package
	 * @param version the version or dist-tag
	 */
	record Version(String packageName, String version) implements NpmRequestPath {
	}

	/**
	 * {@code GET /{package}/-/{file}}: a tarball.
	 *
	 * @param packageName the package name, {@code @scope/name} for a scoped package
	 * @param fileName the tarball file name
	 */
	record Tarball(String packageName, String fileName) implements NpmRequestPath {
	}

	/**
	 * Classify a decoded artifact path.
	 * @param artifactPath the path relative to the repository root, already URL-decoded
	 * @return the classified request
	 * @throws IllegalArgumentException if the path is not an npm registry path or the
	 * package name is malformed; the router turns this into a {@code 400}
	 */
	static NpmRequestPath parse(String artifactPath) {
		if (artifactPath == null || artifactPath.isEmpty()) {
			throw new IllegalArgumentException("An npm request needs a package name");
		}
		if (artifactPath.startsWith("-/")) {
			return switch (artifactPath.substring(2)) {
				case "ping" -> new Ping();
				case "v1/search" -> new Search();
				case "npm/v1/security/audits/quick" -> new Audit();
				default -> throw new IllegalArgumentException("Unsupported npm registry endpoint: " + artifactPath);
			};
		}
		int tarballMarker = artifactPath.indexOf("/-/");
		if (tarballMarker >= 0) {
			String packageName = artifactPath.substring(0, tarballMarker);
			String fileName = artifactPath.substring(tarballMarker + 3);
			validatePackageName(packageName);
			if (fileName.isEmpty() || fileName.indexOf('/') >= 0) {
				throw new IllegalArgumentException("Invalid npm tarball path: " + artifactPath);
			}
			return new Tarball(packageName, fileName);
		}
		String[] segments = artifactPath.split("/", -1);
		if (segments[0].startsWith("@")) {
			if (segments.length < 2 || segments.length > 3) {
				throw new IllegalArgumentException("Invalid scoped npm package path: " + artifactPath);
			}
			String packageName = segments[0] + "/" + segments[1];
			validatePackageName(packageName);
			return segments.length == 2 ? new Packument(packageName)
					: new Version(packageName, validateVersion(segments[2]));
		}
		if (segments.length > 2) {
			throw new IllegalArgumentException("Invalid npm package path: " + artifactPath);
		}
		validatePackageName(segments[0]);
		return segments.length == 1 ? new Packument(segments[0])
				: new Version(segments[0], validateVersion(segments[1]));
	}

	private static String validateVersion(String version) {
		if (version.isEmpty() || version.indexOf('@') >= 0) {
			throw new IllegalArgumentException("Invalid npm version: " + version);
		}
		return version;
	}

	/**
	 * Accept only an unscoped name or an {@code @scope/name} pair. The character set is
	 * the npm one (letters, digits and {@code - . _ ~}); a name that reaches storage is
	 * also validated by {@code ArtifactLocation}, which rejects path traversal.
	 */
	private static void validatePackageName(String packageName) {
		if (packageName.isEmpty() || packageName.length() > 214) {
			throw new IllegalArgumentException("Invalid npm package name: " + packageName);
		}
		if (packageName.startsWith("@")) {
			int slash = packageName.indexOf('/');
			if (slash <= 1 || slash == packageName.length() - 1 || packageName.indexOf('/', slash + 1) >= 0) {
				throw new IllegalArgumentException("Invalid npm scope: " + packageName);
			}
			validateSegment(packageName.substring(1, slash));
			validateSegment(packageName.substring(slash + 1));
			return;
		}
		if (packageName.indexOf('/') >= 0) {
			throw new IllegalArgumentException("Invalid npm package name: " + packageName);
		}
		validateSegment(packageName);
	}

	private static void validateSegment(String segment) {
		if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
			throw new IllegalArgumentException("Invalid npm package name segment: " + segment);
		}
		for (int i = 0; i < segment.length(); i++) {
			char c = segment.charAt(i);
			if (!(Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '_' || c == '~')) {
				throw new IllegalArgumentException("Invalid npm package name: " + segment);
			}
		}
	}

}
