package am.ik.kagami.registry.pypi;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The name of a PyPI project, in the normalized form a simple index uses.
 * <p>
 * PEP 503 normalization lowercases the name and collapses every run of {@code -},
 * {@code _} and {@code .} into a single {@code -}, so {@code Zope.Interface} and
 * {@code zope-interface} address the same index. A name that does not fit the project
 * name character set is rejected; the router turns the exception into a 400 response.
 */
final class PypiProjectName {

	/**
	 * The project name characters allowed by PEP 508. After normalization only lowercase
	 * letters, digits and {@code -} remain.
	 */
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

	private static final Pattern SEPARATORS = Pattern.compile("[-_.]+");

	private PypiProjectName() {
	}

	/**
	 * Validate and normalize a project name.
	 * @param name the name as it appears in the request path
	 * @return the PEP 503 normalized name
	 * @throws IllegalArgumentException if the name is empty or contains a character
	 * outside the project name character set
	 */
	static String normalize(String name) {
		if (name == null || !VALID_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid PyPI project name: '" + name + "'");
		}
		return SEPARATORS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
	}

}
