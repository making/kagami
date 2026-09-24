package am.ik.kagami;

import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageEntry;
import am.ik.kagami.storage.StorageService;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs the browser E2E scenario against S3-compatible object storage
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserE2ETest extends BrowserE2ETestBase {

	@Autowired
	StorageService storageService;

	@BeforeEach
	void clearStorageBeforeTest() throws IOException {
		clearMockRepository();
	}

	@AfterEach
	void clearStorageAfterTest() throws IOException {
		clearMockRepository();
	}

	private void clearMockRepository() throws IOException {
		ArtifactLocation root = ArtifactLocation.root("mock");
		for (StorageEntry entry : this.storageService.list(root)) {
			this.storageService.delete(new ArtifactLocation(root.repositoryId(), entry.path()));
		}
	}

}
