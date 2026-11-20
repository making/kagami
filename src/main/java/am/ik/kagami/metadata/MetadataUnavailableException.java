package am.ik.kagami.metadata;

/**
 * Thrown when an index cannot be served: the upstream failed and no stale body is left
 * within the stale-serving bound, or the upstream body exceeds the configured size.
 * <p>
 * The format handler turns this into a {@code 502 Bad Gateway} response.
 */
public class MetadataUnavailableException extends RuntimeException {

	public MetadataUnavailableException(String message) {
		super(message);
	}

	public MetadataUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
