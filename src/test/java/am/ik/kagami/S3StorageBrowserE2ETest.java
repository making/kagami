package am.ik.kagami;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs the browser E2E scenario against the S3 storage backend.
 */
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

	@DynamicPropertySource
	static void configureStorage(DynamicPropertyRegistry registry) {
		RustfsContainer.registerProperties(registry);
	}

	@BeforeEach
	void clearStorage() {
		RustfsContainer.clearBucket();
	}

}
