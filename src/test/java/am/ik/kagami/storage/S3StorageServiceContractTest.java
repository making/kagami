package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.StorageType;
import am.ik.kagami.RustfsContainer;
import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the storage contract against the S3 backend, backed by a rustfs container. A fresh
 * bucket is created for every test so that each test starts from empty storage.
 */
class S3StorageServiceContractTest extends StorageServiceContractTest {

	static RustfsContainer container;

	S3Client client;

	StorageService service;

	@BeforeAll
	static void startContainer() {
		container = new RustfsContainer();
		container.start();
	}

	@AfterAll
	static void stopContainer() {
		container.close();
	}

	@BeforeEach
	void createBucket() {
		String bucket = "contract-" + UUID.randomUUID().toString().replace("-", "");
		container.createBucket(bucket);
		this.client = container.client();
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder()
				.type(StorageType.S3)
				.s3(new KagamiProperties.S3(bucket, null))
				.build())
			.repositories(Map.of())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		this.service = new S3StorageService(properties, this.client,
				new InMemoryBufferingS3OutputStreamProvider(this.client, null));
	}

	@AfterEach
	void closeClient() {
		this.client.close();
	}

	@Override
	protected StorageService storageService() {
		return this.service;
	}

}
