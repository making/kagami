package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.S3TestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the storage contract against the S3 backend, using a fresh key prefix per test so
 * that every test observes empty storage.
 */
class S3StorageServiceTest extends StorageServiceContractTest {

	@BeforeAll
	static void createBucket() {
		S3TestSupport.createBucket(S3TestSupport.BUCKET);
	}

	@Override
	protected StorageService storageService() {
		S3Client s3Client = S3TestSupport.newClient();
		KagamiProperties properties = KagamiProperties.builder()
			.storage(new KagamiProperties.Storage(KagamiProperties.Storage.StorageType.S3, "",
					new KagamiProperties.Storage.S3(S3TestSupport.BUCKET, "contract-" + UUID.randomUUID())))
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new S3StorageService(s3Client, properties);
	}

}
