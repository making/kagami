package am.ik.kagami.storage;

import java.io.InputStream;
import java.time.Instant;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * A {@link Resource} backed by a single S3 object.
 * <p>
 * The metadata (content length and last modified) is captured once by a
 * {@code HeadObject} in {@link S3StorageService#retrieve(ArtifactLocation)} so that
 * callers such as {@code ArtifactController} can call {@link #exists()} and
 * {@link #contentLength()} without triggering further round trips. The content itself is
 * streamed lazily via a {@code GetObject}.
 */
class S3ObjectResource extends AbstractResource {

	private final S3Client s3Client;

	private final String bucket;

	private final String key;

	private final String filename;

	private final long contentLength;

	private final Instant lastModified;

	S3ObjectResource(S3Client s3Client, String bucket, String key, String filename, long contentLength,
			Instant lastModified) {
		this.s3Client = s3Client;
		this.bucket = bucket;
		this.key = key;
		this.filename = filename;
		this.contentLength = contentLength;
		this.lastModified = lastModified;
	}

	@Override
	public InputStream getInputStream() {
		return this.s3Client.getObject(GetObjectRequest.builder().bucket(this.bucket).key(this.key).build(),
				ResponseTransformer.toInputStream());
	}

	@Override
	public String getFilename() {
		return this.filename;
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
	public boolean isReadable() {
		return true;
	}

	@Override
	public String getDescription() {
		return "S3 object [" + this.bucket + "/" + this.key + "]";
	}

}
