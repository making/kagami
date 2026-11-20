package am.ik.kagami.storage;

import org.jspecify.annotations.Nullable;

/**
 * A {@link org.springframework.core.io.Resource} that carries the content type persisted
 * with the stored object.
 * <p>
 * A backend that keeps object metadata (S3) can return the Content-Type the artifact was
 * stored with, which may be the upstream Content-Type rather than one derived from the
 * file extension. Backends without object metadata simply do not implement this.
 */
public interface ContentTypeAware {

	/**
	 * The content type recorded when the object was stored.
	 * @return the stored content type, or {@code null} when none was recorded
	 */
	@Nullable String getContentType();

}
