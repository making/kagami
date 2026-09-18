package am.ik.kagami.storage;

import java.net.URI;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3-compatible test server backed by rustfs. Used by every test that exercises the S3
 * storage backend.
 */
public final class Rustfs {

	public static final String ACCESS_KEY = "kagami-test";

	public static final String SECRET_KEY = "kagami-test-secret";

	private static final int PORT = 9000;

	private Rustfs() {
	}

	public static GenericContainer<?> container() {
		return new GenericContainer<>("rustfs/rustfs:1.0.0-rc.5").withEnv("RUSTFS_VOLUMES", "/data")
			.withEnv("RUSTFS_ADDRESS", "0.0.0.0:" + PORT)
			.withEnv("RUSTFS_CONSOLE_ENABLE", "false")
			.withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
			.withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
			.withExposedPorts(PORT)
			.waitingFor(Wait.forHttp("/health").forPort(PORT).withStartupTimeout(Duration.ofMinutes(2)));
	}

	public static String endpoint(GenericContainer<?> rustfs) {
		return "http://%s:%d".formatted(rustfs.getHost(), rustfs.getMappedPort(PORT));
	}

	public static S3Client client(GenericContainer<?> rustfs) {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint(rustfs)))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.forcePathStyle(true)
			.build();
	}

	public static void createBucket(S3Client s3Client, String bucket) {
		s3Client.createBucket(builder -> builder.bucket(bucket));
	}

	public static void putObject(S3Client s3Client, String bucket, String key, String content) {
		s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString(content));
	}

}
