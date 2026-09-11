package am.ik.kagami;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the browser E2E scenario against the S3 storage backend.
 */
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		S3TestSupport.registerS3Properties(registry, "e2e");
	}

}
