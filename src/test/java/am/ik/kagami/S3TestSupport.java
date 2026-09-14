package am.ik.kagami;

import java.net.URI;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Shared rustfs S3-compatible server for the S3-backed tests. The container is started
 * once per JVM and every test class isolates its objects with its own
 * {@code kagami.storage.s3.key-prefix}.
 */
public final class S3TestSupport {

	public static final String ACCESS_KEY = "kagami";

	public static final String SECRET_KEY = "kagami-secret-key";

	public static final String REGION = "us-east-1";

	public static final String BUCKET = "kagami-test";

	private static final GenericContainer<?> RUSTFS = new GenericContainer<>(
			DockerImageName.parse("rustfs/rustfs:1.0.0-rc.5"))
		.withExposedPorts(9000)
		.withEnv("RUSTFS_VOLUMES", "/data")
		.withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
		.withEnv("RUSTFS_CONSOLE_ENABLE", "false")
		.withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
		.withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
		.waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

	private S3TestSupport() {
	}

	public static synchronized String endpoint() {
		if (!RUSTFS.isRunning()) {
			RUSTFS.start();
		}
		return "http://%s:%d".formatted(RUSTFS.getHost(), RUSTFS.getMappedPort(9000));
	}

	public static S3Client newClient() {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint()))
			.region(Region.of(REGION))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.forcePathStyle(true)
			.build();
	}

	public static void createBucket(String bucket) {
		try (S3Client client = newClient()) {
			try {
				client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
			}
			catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException e) {
				// already created by another test class
			}
		}
	}

	public static void registerS3Properties(DynamicPropertyRegistry registry, String keyPrefix) {
		String endpoint = endpoint();
		createBucket(BUCKET);
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("kagami.storage.s3.key-prefix", () -> keyPrefix);
		registry.add("spring.cloud.aws.s3.endpoint", () -> endpoint);
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
		registry.add("spring.cloud.aws.region.static", () -> REGION);
		registry.add("spring.cloud.aws.credentials.access-key", () -> ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> SECRET_KEY);
	}

}
