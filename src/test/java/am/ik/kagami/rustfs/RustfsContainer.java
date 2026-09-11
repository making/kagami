package am.ik.kagami.rustfs;

import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import io.awspring.cloud.s3.PropertiesS3ObjectContentTypeResolver;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import java.net.URI;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * An S3-compatible server backing the tests of the S3 storage backend.
 * <p>
 * A single container is started lazily and shared by every test class in the JVM;
 * isolation comes from a fresh bucket per test instead.
 */
public final class RustfsContainer {

	private static final int PORT = 9000;

	private static final String ACCESS_KEY = "kagami";

	private static final String SECRET_KEY = "kagami-secret";

	private static final String REGION = "us-east-1";

	private static final GenericContainer<?> container = new GenericContainer<>("rustfs/rustfs:1.0.0-rc.5")
		.withExposedPorts(PORT)
		.withEnv("RUSTFS_VOLUMES", "/data")
		.withEnv("RUSTFS_ADDRESS", "0.0.0.0:" + PORT)
		.withEnv("RUSTFS_CONSOLE_ENABLE", "false")
		.withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
		.withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
		.waitingFor(Wait.forHttp("/health").forPort(PORT).forStatusCode(200));

	private RustfsContainer() {
	}

	/**
	 * Start the shared container unless it is already running and return its S3 endpoint.
	 * @return the endpoint URL
	 */
	public static String endpoint() {
		container.start();
		return "http://%s:%d".formatted(container.getHost(), container.getMappedPort(PORT));
	}

	/**
	 * Create a bucket nothing else uses.
	 * @return the name of the created bucket
	 */
	public static String createBucket() {
		String bucket = "kagami-" + UUID.randomUUID();
		s3Client().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
		return bucket;
	}

	/**
	 * A client for the shared container, configured the way the application configures
	 * its own.
	 * @return the client
	 */
	public static S3Client s3Client() {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint()))
			.region(Region.of(REGION))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.forcePathStyle(true)
			.build();
	}

	/**
	 * The upload strategy the application auto-configures.
	 * @param s3Client the client to upload with
	 * @return the output stream provider
	 */
	public static S3OutputStreamProvider outputStreamProvider(S3Client s3Client) {
		return new InMemoryBufferingS3OutputStreamProvider(s3Client, new PropertiesS3ObjectContentTypeResolver());
	}

	/**
	 * Point the application at the shared container and at the given bucket.
	 * @param registry the registry of the test
	 * @param bucket the bucket the artifacts are stored in
	 */
	public static void registerProperties(DynamicPropertyRegistry registry, String bucket) {
		registry.add("kagami.storage.s3.bucket", () -> bucket);
		registry.add("spring.cloud.aws.s3.endpoint", RustfsContainer::endpoint);
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> true);
		registry.add("spring.cloud.aws.region.static", () -> REGION);
		registry.add("spring.cloud.aws.credentials.access-key", () -> ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> SECRET_KEY);
	}

}
