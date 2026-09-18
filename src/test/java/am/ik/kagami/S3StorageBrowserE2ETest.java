package am.ik.kagami;

import am.ik.kagami.storage.Rustfs;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Runs the browser E2E scenario against the S3 storage backend on rustfs
 */
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

	static final GenericContainer<?> RUSTFS = Rustfs.container();

	static final String BUCKET = "kagami-e2e-" + UUID.randomUUID();

	static {
		RUSTFS.start();
		Rustfs.createBucket(Rustfs.client(RUSTFS), BUCKET);
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
		registry.add("spring.cloud.aws.credentials.access-key", () -> Rustfs.ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> Rustfs.SECRET_KEY);
		registry.add("spring.cloud.aws.s3.endpoint", () -> Rustfs.endpoint(RUSTFS));
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
	}

}
