package am.ik.kagami;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the browser E2E scenario against the S3 storage backend
 */
class S3BrowserE2ETest extends BrowserE2ETestBase {

	static final String BUCKET = "kagami-e2e";

	static final RustfsContainer container = new RustfsContainer();

	@AfterAll
	static void stopContainer() {
		container.close();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		if (!container.isRunning()) {
			container.start();
			container.createBucket(BUCKET);
		}
		registry.add("kagami.storage.type", () -> "s3");
		registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		registry.add("spring.cloud.aws.s3.enabled", () -> "true");
		registry.add("spring.cloud.aws.s3.endpoint", () -> container.endpoint());
		registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
		registry.add("spring.cloud.aws.region.static", () -> RustfsContainer.REGION);
		registry.add("spring.cloud.aws.credentials.access-key", () -> RustfsContainer.ACCESS_KEY);
		registry.add("spring.cloud.aws.credentials.secret-key", () -> RustfsContainer.SECRET_KEY);
	}

}
