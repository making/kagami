package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.S3TestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the storage contract against the S3 backend. Every test gets its own key prefix so
 * that tests are isolated within the shared bucket.
 */
class S3StorageServiceTest extends StorageServiceContractTest {

	private static S3Client s3Client;

	@BeforeAll
	static void createBucket() {
		s3Client = S3TestSupport.newClient();
		S3TestSupport.createBucket(S3TestSupport.BUCKET);
	}

	@Override
	protected StorageService storageService() {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(new KagamiProperties.Storage(KagamiProperties.Storage.StorageType.S3, "/tmp/unused",
					new KagamiProperties.Storage.S3(S3TestSupport.BUCKET, "contract-" + UUID.randomUUID())))
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new S3StorageService(s3Client, properties);
	}

}
