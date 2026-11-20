package am.ik.kagami.registry;

import java.util.Locale;

/**
 * The package format served by a mirrored repository.
 * <p>
 * The value of {@code kagami.repositories.<id>.format} selects the format. An unknown
 * value is rejected when the application starts rather than when the first request
 * arrives.
 */
public enum Format {

	/** Maven 2 repository layout, the historical and default behavior. */
	MAVEN("maven"),

	/** npm registry. */
	NPM("npm"),

	/** PyPI simple index. */
	PYPI("pypi");

	private final String value;

	Format(String value) {
		this.value = value;
	}

	/**
	 * The configuration value that selects this format.
	 * @return the format name used in {@code kagami.repositories.<id>.format}
	 */
	public String value() {
		return this.value;
	}

	/**
	 * Parse the configured format value.
	 * @param format the configuration value, e.g. {@code maven}
	 * @return the matching format
	 * @throws IllegalArgumentException if the value does not name a known format
	 */
	public static Format parse(String format) {
		String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "", "maven" -> MAVEN;
			case "npm" -> NPM;
			case "pypi" -> PYPI;
			default -> throw new IllegalArgumentException("Unknown repository format: '" + format + "'");
		};
	}

}
