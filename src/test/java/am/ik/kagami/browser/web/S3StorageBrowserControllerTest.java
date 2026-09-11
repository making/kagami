package am.ik.kagami.browser.web;

import am.ik.kagami.TestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs the browser API tests against S3-compatible object storage
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserControllerTest extends BrowserControllerTestBase {

}
