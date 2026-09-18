package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.Rustfs;
import io.awspring.cloud.s3.S3Template;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the storage contract against the S3 backend backed by the rustfs test server.
 * <p>
 * Every {@link #storageService()} receives a unique key prefix so that the shared bucket
 * gives each test its own empty namespace.
 */
class S3StorageServiceContractTest extends StorageServiceContractTest {

	private static S3Client s3Client;

	private static S3Template s3Template;

	@BeforeAll
	static void start() {
		Rustfs.container();
		s3Client = Rustfs.client();
		s3Template = Rustfs.template();
	}

	@AfterAll
	static void stop() {
		s3Client.close();
	}

	@Override
	protected StorageService storageService() {
		String keyPrefix = "test-" + UUID.randomUUID() + "/";
		KagamiProperties properties = KagamiProperties.builder()
			.storage(new KagamiProperties.Storage(KagamiProperties.StorageType.S3, null,
					new KagamiProperties.Storage.S3(Rustfs.bucket(), keyPrefix)))
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new S3StorageService(s3Client, s3Template, properties);
	}

}
