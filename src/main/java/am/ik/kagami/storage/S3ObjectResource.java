package am.ik.kagami.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.AbstractResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * A single S3 object whose metadata is already known.
 * <p>
 * The metadata comes from the {@code HeadObject} that proved the object exists, so that
 * reading {@link #contentLength()} or {@link #lastModified()} afterwards costs no
 * additional request.
 */
class S3ObjectResource extends AbstractResource {

	private final S3Client s3Client;

	private final String bucket;

	private final String key;

	private final long contentLength;

	private final Instant lastModified;

	private S3ObjectResource(S3Client s3Client, String bucket, String key, long contentLength, Instant lastModified) {
		this.s3Client = s3Client;
		this.bucket = bucket;
		this.key = key;
		this.contentLength = contentLength;
		this.lastModified = lastModified;
	}

	static Builder builder() {
		return new Builder();
	}

	@Override
	public boolean exists() {
		return true;
	}

	@Override
	public long contentLength() {
		return this.contentLength;
	}

	@Override
	public long lastModified() {
		return this.lastModified.toEpochMilli();
	}

	@Override
	public String getFilename() {
		int lastSlash = this.key.lastIndexOf('/');
		return lastSlash < 0 ? this.key : this.key.substring(lastSlash + 1);
	}

	@Override
	public String getDescription() {
		return "S3 object [s3://%s/%s]".formatted(this.bucket, this.key);
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this.s3Client.getObject(GetObjectRequest.builder().bucket(this.bucket).key(this.key).build());
	}

	static final class Builder {

		@Nullable private S3Client s3Client;

		@Nullable private String bucket;

		@Nullable private String key;

		private long contentLength;

		@Nullable private Instant lastModified;

		private Builder() {
		}

		Builder s3Client(S3Client s3Client) {
			this.s3Client = s3Client;
			return this;
		}

		Builder bucket(String bucket) {
			this.bucket = bucket;
			return this;
		}

		Builder key(String key) {
			this.key = key;
			return this;
		}

		Builder contentLength(long contentLength) {
			this.contentLength = contentLength;
			return this;
		}

		Builder lastModified(Instant lastModified) {
			this.lastModified = lastModified;
			return this;
		}

		S3ObjectResource build() {
			return new S3ObjectResource(Objects.requireNonNull(this.s3Client, "s3Client is required"),
					Objects.requireNonNull(this.bucket, "bucket is required"),
					Objects.requireNonNull(this.key, "key is required"), this.contentLength,
					Objects.requireNonNull(this.lastModified, "lastModified is required"));
		}

	}

}
