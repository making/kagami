package am.ik.kagami;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A single rustfs container shared by every S3 test. It is started lazily on first access
 * and left running for the whole test JVM; Testcontainers' Ryuk reaper removes it
 * afterwards.
 */
public final class RustfsContainer {

	public static final String BUCKET = "kagami-test";

	private static final int PORT = 9000;

	private static final String ACCESS_KEY = "kagami-access-key";

	private static final String SECRET_KEY = "kagami-secret-key";

	private static final String REGION = "us-east-1";

	private static @Nullable GenericContainer<?> container;

	private RustfsContainer() {
	}

	public static synchronized GenericContainer<?> get() {
		if (container == null) {
			GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.0-rc.5"))
				.withExposedPorts(PORT)
				.withEnv("RUSTFS_VOLUMES", "/data")
				.withEnv("RUSTFS_ADDRESS", "0.0.0.0:" + PORT)
				.withEnv("RUSTFS_CONSOLE_ENABLE", "false")
				.withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
				.withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
				.waitingFor(Wait.forHttp("/health")
					.forPort(PORT)
					.forStatusCode(200)
					.withStartupTimeout(Duration.ofMinutes(2)));
			started.start();
			container = started;
		}
		return container;
	}

	public static String endpoint() {
		GenericContainer<?> running = get();
		return "http://%s:%d".formatted(running.getHost(), running.getMappedPort(PORT));
	}

	public static S3Client client() {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint()))
			.region(Region.of(REGION))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	public static void ensureBucket() {
		try (S3Client client = client()) {
			boolean exists = client.listBuckets().buckets().stream().anyMatch(bucket -> bucket.name().equals(BUCKET));
			if (!exists) {
				client.createBucket(request -> request.bucket(BUCKET));
			}
		}
	}

	public static void clearBucket() {
		try (S3Client client = client()) {
			for (S3Object object : client.listObjectsV2Paginator(request -> request.bucket(BUCKET)).contents()) {
				client.deleteObject(request -> request.bucket(BUCKET).key(object.key()));
			}
		}
	}

	/**
	 * Start the container, create the bucket and register the properties the S3
	 * auto-configuration reads.
	 */
	public static void registerProperties(DynamicPropertyRegistry registry) {
		ensureBucket();
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("spring.cloud.aws.s3.endpoint", RustfsContainer::endpoint);
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> true);
		registry.add("spring.cloud.aws.region.static", () -> REGION);
		registry.add("spring.cloud.aws.credentials.access-key", () -> ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> SECRET_KEY);
	}

}
