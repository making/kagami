package am.ik.kagami.repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * A digest that an upstream body is expected to match.
 * <p>
 * Upstream indexes publish content hashes in several notations: PyPI names a SHA-256 in
 * the file link fragment, npm publishes a Subresource Integrity value in
 * {@code dist.integrity} and a SHA-1 {@code dist.shasum}. Both handlers describe the
 * expected value with this type so that {@link RemoteRepositoryService} streams the body
 * through a single verification path.
 *
 * @param algorithm the JCA digest algorithm, for example {@code SHA-512}
 * @param hex the expected digest in lower-case hexadecimal
 */
public record ExpectedDigest(String algorithm, String hex) {

	/** SRI algorithms from the strongest to the weakest. */
	private static final List<String> SRI_PREFERENCE = List.of("sha512", "sha384", "sha256", "sha1");

	public ExpectedDigest {
		if (!StringUtils.hasText(algorithm) || !StringUtils.hasText(hex)) {
			throw new IllegalArgumentException("A digest needs both an algorithm and a value");
		}
		hex = hex.toLowerCase(Locale.ROOT);
	}

	/**
	 * An expected SHA-256 given as hexadecimal text, as PyPI publishes it. A missing or
	 * blank value yields {@link Optional#empty()}, which disables verification.
	 * @param hex the expected SHA-256 in hexadecimal
	 * @return the digest, or empty when no hash was published
	 */
	public static Optional<ExpectedDigest> sha256Hex(@Nullable String hex) {
		return StringUtils.hasText(hex) ? Optional.of(new ExpectedDigest("SHA-256", hex)) : Optional.empty();
	}

	/**
	 * Parse a Subresource Integrity value such as
	 * {@code sha512-<base64> sha384-<base64>}. When several tokens are present the
	 * strongest supported algorithm is used, as SRI requires. Tokens with an unsupported
	 * algorithm or a digest of the wrong length are ignored.
	 * @param integrity the SRI value, or {@code null}
	 * @return the strongest usable digest, or empty when none could be parsed
	 */
	public static Optional<ExpectedDigest> fromSri(@Nullable String integrity) {
		if (!StringUtils.hasText(integrity)) {
			return Optional.empty();
		}
		String[] tokens = integrity.trim().split("\\s+");
		for (String algorithm : SRI_PREFERENCE) {
			for (String token : tokens) {
				Optional<ExpectedDigest> digest = parseSriToken(algorithm, token);
				if (digest.isPresent()) {
					return digest;
				}
			}
		}
		return Optional.empty();
	}

	private static Optional<ExpectedDigest> parseSriToken(String algorithm, String token) {
		String prefix = algorithm + "-";
		if (!token.startsWith(prefix)) {
			return Optional.empty();
		}
		String value = token.substring(prefix.length());
		int options = value.indexOf('?');
		if (options >= 0) {
			value = value.substring(0, options);
		}
		if (value.isEmpty()) {
			return Optional.empty();
		}
		byte[] digest;
		try {
			digest = Base64.getDecoder().decode(value);
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		ExpectedDigest expected = new ExpectedDigest(jcaAlgorithm(algorithm), HexFormat.of().formatHex(digest));
		if (digest.length != expected.newDigest().getDigestLength()) {
			return Optional.empty();
		}
		return Optional.of(expected);
	}

	/**
	 * A SHA-1 digest given as hexadecimal text, as npm publishes it in
	 * {@code dist.shasum}.
	 * @param shasum the expected SHA-1 in hexadecimal
	 * @return the digest, or empty when the value is missing or malformed
	 */
	public static Optional<ExpectedDigest> fromShasum(@Nullable String shasum) {
		if (!StringUtils.hasText(shasum) || shasum.trim().length() != 40) {
			return Optional.empty();
		}
		return Optional.of(new ExpectedDigest("SHA-1", shasum.trim()));
	}

	private static String jcaAlgorithm(String sriAlgorithm) {
		return switch (sriAlgorithm) {
			case "sha512" -> "SHA-512";
			case "sha384" -> "SHA-384";
			case "sha256" -> "SHA-256";
			case "sha1" -> "SHA-1";
			default -> throw new IllegalArgumentException("Unsupported SRI algorithm: " + sriAlgorithm);
		};
	}

	/**
	 * Create a fresh digest instance to update while the body streams into storage.
	 * @return the digest instance
	 */
	public MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance(this.algorithm);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(this.algorithm + " is not available", e);
		}
	}

	/**
	 * Whether the completed digest matches this expected value.
	 * @param digest the digest that was updated with the body
	 * @return true when the body matches
	 */
	public boolean matches(MessageDigest digest) {
		return this.hex.equals(HexFormat.of().formatHex(digest.digest()));
	}

}
