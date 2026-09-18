package am.ik.kagami;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the browser E2E scenario from {@link BrowserE2ETestBase} against the S3-backed
 * storage served by the rustfs test server.
 */
@Import(MockConfig.class)
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

	@DynamicPropertySource
	static void configureS3Storage(DynamicPropertyRegistry registry) {
		Rustfs.registerProperties(registry);
	}

}
