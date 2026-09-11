package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the storage contract against the local file system backend
 */
class LocalStorageServiceTest extends StorageServiceContractTest {

	@TempDir
	Path tempDir;

	@Override
	protected StorageService storageService() {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(new KagamiProperties.Storage(KagamiProperties.Storage.StorageType.LOCAL, this.tempDir.toString(),
					null))
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new LocalStorageService(properties);
	}

}
