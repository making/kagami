package am.ik.kagami;

import am.ik.kagami.rustfs.RustfsContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs the browser E2E scenario against an S3-compatible object storage.
 * <p>
 * The storage type is set with {@link TestPropertySource} rather than with
 * {@link DynamicPropertySource} because it has to be in place before the environment is
 * post-processed, which is where the backend specific defaults are contributed.
 */
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

	private static final String BUCKET = RustfsContainer.createBucket();

	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		RustfsContainer.registerProperties(registry, BUCKET);
	}

}
