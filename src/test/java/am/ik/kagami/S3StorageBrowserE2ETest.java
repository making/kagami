package am.ik.kagami;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs the browser E2E scenario against S3-compatible object storage
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

}
