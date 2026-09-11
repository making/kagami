package am.ik.kagami.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.AbstractResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * A {@link org.springframework.core.io.Resource} over an S3 object whose metadata has
 * already been fetched. The object is known to exist, so {@link #exists()},
 * {@link #contentLength()} and {@link #lastModified()} answer from the metadata without
 * another request; only {@link #getInputStream()} talks to S3.
 */
final class S3ObjectResource extends AbstractResource {

	private final S3Client s3Client;

	private final String bucket;

	private final String key;

	private final String filename;

	private final long contentLength;

	private final Instant lastModified;

	private S3ObjectResource(Builder builder) {
		this.s3Client = Objects.requireNonNull(builder.s3Client, "s3Client is required");
		this.bucket = Objects.requireNonNull(builder.bucket, "bucket is required");
		this.key = Objects.requireNonNull(builder.key, "key is required");
		this.filename = Objects.requireNonNull(builder.filename, "filename is required");
		this.contentLength = Objects.requireNonNull(builder.contentLength, "contentLength is required");
		this.lastModified = Objects.requireNonNull(builder.lastModified, "lastModified is required");
	}

	static Builder builder() {
		return new Builder();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		try {
			return this.s3Client.getObject(request -> request.bucket(this.bucket).key(this.key));
		}
		catch (NoSuchKeyException e) {
			throw new FileNotFoundException(getDescription() + " no longer exists");
		}
	}

	@Override
	public boolean exists() {
		return true;
	}

	@Override
	public boolean isReadable() {
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
		return this.filename;
	}

	@Override
	public String getDescription() {
		return "S3 object [s3://%s/%s]".formatted(this.bucket, this.key);
	}

	static final class Builder {

		@Nullable private S3Client s3Client;

		@Nullable private String bucket;

		@Nullable private String key;

		@Nullable private String filename;

		@Nullable private Long contentLength;

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

		Builder filename(String filename) {
			this.filename = filename;
			return this;
		}

		Builder contentLength(Long contentLength) {
			this.contentLength = contentLength;
			return this;
		}

		Builder lastModified(Instant lastModified) {
			this.lastModified = lastModified;
			return this;
		}

		S3ObjectResource build() {
			return new S3ObjectResource(this);
		}

	}

}
