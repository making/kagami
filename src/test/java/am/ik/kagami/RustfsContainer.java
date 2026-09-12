package am.ik.kagami;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * rustfs container, a S3-compatible object store, used to exercise the S3 storage
 * backend.
 * <p>
 * The bucket is not created by the container itself (rustfs has no bucket-creation API),
 * so the caller must invoke {@link #createBucket(String)} for each bucket a test needs.
 */
public class RustfsContainer extends GenericContainer<RustfsContainer> {

	public static final DockerImageName IMAGE = DockerImageName.parse("rustfs/rustfs:1.0.0-rc.5");

	public static final int PORT = 9000;

	public static final String ACCESS_KEY = "kagami";

	public static final String SECRET_KEY = "kagami-secret";

	public static final String REGION = "us-east-1";

	public RustfsContainer() {
		super(IMAGE);
		withExposedPorts(PORT);
		withEnv("RUSTFS_VOLUMES", "/data");
		withEnv("RUSTFS_ADDRESS", "0.0.0.0:" + PORT);
		withEnv("RUSTFS_CONSOLE_ENABLE", "false");
		withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY);
		withEnv("RUSTFS_SECRET_KEY", SECRET_KEY);
		waitingFor(Wait.forHttp("/health").forPort(PORT).forStatusCode(200));
	}

	/**
	 * @return the endpoint URL of the S3 API, e.g. {@code http://localhost:32987}
	 */
	public String endpoint() {
		return "http://%s:%d".formatted(getHost(), getMappedPort(PORT));
	}

	/**
	 * Create a bucket if it does not exist yet.
	 * @param bucket the bucket name
	 */
	public void createBucket(String bucket) {
		try (S3Client client = client()) {
			client.createBucket(builder -> builder.bucket(bucket));
		}
		catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
			// Idempotent: the bucket already exists
		}
	}

	/**
	 * Delete every object in the given bucket.
	 * @param bucket the bucket name
	 */
	public void deleteAllObjects(String bucket) {
		try (S3Client client = client()) {
			List<ObjectIdentifier> keys = new ArrayList<>();
			String token = null;
			do {
				ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket);
				if (token != null) {
					request.continuationToken(token);
				}
				ListObjectsV2Response page = client.listObjectsV2(request.build());
				for (var object : page.contents()) {
					keys.add(ObjectIdentifier.builder().key(object.key()).build());
				}
				token = page.isTruncated() ? page.nextContinuationToken() : null;
			}
			while (token != null);
			if (!keys.isEmpty()) {
				client.deleteObjects(
						DeleteObjectsRequest.builder().bucket(bucket).delete(delete -> delete.objects(keys)).build());
			}
		}
	}

	/**
	 * Build an S3 client that talks to this container with path-style access.
	 * @return a new, caller-managed S3 client
	 */
	public S3Client client() {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint()))
			.forcePathStyle(true)
			.region(Region.of(REGION))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.build();
	}

}
