package am.ik.kagami;

import com.github.dockerjava.api.command.InspectContainerResponse;
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

/**
 * S3-compatible object storage for tests, backed by a single-node
 * <a href="https://rustfs.com">RustFS</a> container. Buckets registered with
 * {@link #withBucket(String)} are created as soon as the container is up.
 */
public class RustFsContainer extends GenericContainer<RustFsContainer> {

	public static final DockerImageName IMAGE = DockerImageName.parse("rustfs/rustfs:1.0.0-rc.5");

	public static final int PORT = 9000;

	public static final String ACCESS_KEY = "rustfsadmin";

	public static final String SECRET_KEY = "rustfsadmin";

	public static final String REGION = "us-east-1";

	private final List<String> buckets = new ArrayList<>();

	public RustFsContainer() {
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
	 * Create the bucket once the container has started.
	 * @param bucket the bucket name
	 * @return this container
	 */
	public RustFsContainer withBucket(String bucket) {
		this.buckets.add(bucket);
		return self();
	}

	@Override
	protected void containerIsStarted(InspectContainerResponse containerInfo) {
		if (this.buckets.isEmpty()) {
			return;
		}
		try (S3Client s3Client = newS3Client()) {
			for (String bucket : this.buckets) {
				s3Client.createBucket(request -> request.bucket(bucket));
			}
		}
	}

	public URI endpoint() {
		return URI.create("http://%s:%d".formatted(getHost(), getMappedPort(PORT)));
	}

	/**
	 * Build a client for this container with the same settings the application is given
	 * through {@code spring.cloud.aws.*}. The caller closes it.
	 * @return a new client
	 */
	public S3Client newS3Client() {
		return S3Client.builder()
			.endpointOverride(endpoint())
			.region(Region.of(REGION))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.forcePathStyle(true)
			.build();
	}

}
