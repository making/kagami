package am.ik.kagami.storage;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the storage contract against the S3 backend on rustfs. Every test gets its own key
 * prefix so that it starts from empty storage.
 */
class S3StorageServiceTest extends StorageServiceContractTest {

	static final GenericContainer<?> RUSTFS = Rustfs.container();

	static S3Client s3Client;

	static String bucket;

	@BeforeAll
	static void startRustfs() {
		RUSTFS.start();
		bucket = "kagami-contract-" + UUID.randomUUID();
		s3Client = Rustfs.client(RUSTFS);
		Rustfs.createBucket(s3Client, bucket);
	}

	@AfterAll
	static void stopRustfs() {
		if (s3Client != null) {
			s3Client.close();
		}
		RUSTFS.stop();
	}

	@Override
	protected StorageService storageService() {
		// A unique key prefix isolates every test from the others
		return new S3StorageService(s3Client, bucket, "test-" + UUID.randomUUID() + "/");
	}

}
