package am.ik.kagami;

import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import io.awspring.cloud.s3.Jackson2JsonS3ObjectConverter;
import io.awspring.cloud.s3.S3Template;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Test helper that starts a single rustfs S3-compatible server per JVM and provisions the
 * bucket used by the S3-backed tests. The container is shared across test classes that
 * run in the same process.
 */
public final class Rustfs {

	public static final String BUCKET = "kagami";

	private static final String ACCESS_KEY = "kagami";

	private static final String SECRET_KEY = "kagami-secret";

	private static @Nullable GenericContainer<?> container;

	private Rustfs() {
	}

	public static synchronized GenericContainer<?> container() {
		if (container == null) {
			container = new GenericContainer<>("rustfs/rustfs:1.0.0-rc.5").withExposedPorts(9000)
				.withEnv("RUSTFS_VOLUMES", "/data")
				.withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
				.withEnv("RUSTFS_CONSOLE_ENABLE", "false")
				.withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
				.withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
				.waitingFor(Wait.forHttp("/health").forStatusCode(200).forPort(9000));
			container.start();
			createBucket();
		}
		return container;
	}

	public static String endpoint() {
		return "http://%s:%d".formatted(container().getHost(), container().getMappedPort(9000));
	}

	public static String accessKey() {
		return ACCESS_KEY;
	}

	public static String secretKey() {
		return SECRET_KEY;
	}

	public static String bucket() {
		return BUCKET;
	}

	public static S3Client client() {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint()))
			.region(Region.US_EAST_1)
			.credentialsProvider(staticCredentialsProvider())
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	public static S3Template template() {
		S3Client client = client();
		return new S3Template(client, new InMemoryBufferingS3OutputStreamProvider(client, null),
				new Jackson2JsonS3ObjectConverter(tools.jackson.databind.json.JsonMapper.builder().build()),
				S3Presigner.builder()
					.endpointOverride(URI.create(endpoint()))
					.region(Region.US_EAST_1)
					.credentialsProvider(staticCredentialsProvider())
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build());
	}

	private static StaticCredentialsProvider staticCredentialsProvider() {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
	}

	private static void createBucket() {
		S3Client client = client();
		try {
			client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
		}
		finally {
			client.close();
		}
	}

	/**
	 * Register the AWS endpoint/credential properties pointing at the container, together
	 * with the Kagami S3 storage settings.
	 */
	public static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		container();
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", Rustfs::bucket);
		registry.add("spring.cloud.aws.s3.endpoint", Rustfs::endpoint);
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
		registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
		registry.add("spring.cloud.aws.credentials.access-key", Rustfs::accessKey);
		registry.add("spring.cloud.aws.credentials.secret-key", Rustfs::secretKey);
	}

}
