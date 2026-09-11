package am.ik.kagami.browser.web;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the browser API tests against the local file system storage
 */
class LocalStorageBrowserControllerTest extends BrowserControllerTestBase {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

}
