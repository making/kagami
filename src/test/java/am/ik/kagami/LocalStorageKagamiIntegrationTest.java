package am.ik.kagami;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the artifact API integration tests against the local file system storage
 */
class LocalStorageKagamiIntegrationTest extends KagamiIntegrationTestBase {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

}
