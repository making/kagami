package am.ik.kagami.metadata;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;

/**
 * An index document fetched from an upstream repository, held in the
 * {@link MetadataCache}.
 *
 * @param body the document body
 * @param contentType the Content-Type the upstream sent, if any
 * @param etag the ETag the upstream sent, used for the next conditional refetch
 * @param lastModified the Last-Modified value the upstream sent, used for the next
 * conditional refetch
 * @param fetchedAt when the body was fetched
 */
public record MetadataDocument(byte[] body, @Nullable MediaType contentType, @Nullable String etag,
		@Nullable String lastModified, Instant fetchedAt) {

	public MetadataDocument {
		Objects.requireNonNull(body, "body is required");
		Objects.requireNonNull(fetchedAt, "fetchedAt is required");
	}

	public int contentLength() {
		return this.body.length;
	}

}
