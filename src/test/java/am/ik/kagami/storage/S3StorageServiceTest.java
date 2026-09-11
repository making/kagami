package am.ik.kagami.storage;

import am.ik.kagami.rustfs.RustfsContainer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the storage contract against an S3-compatible object storage
 */
class S3StorageServiceTest extends StorageServiceContractTest {

	@Override
	protected StorageService storageService() {
		S3Client s3Client = RustfsContainer.s3Client();
		return S3StorageService.builder()
			.s3Client(s3Client)
			.outputStreamProvider(RustfsContainer.outputStreamProvider(s3Client))
			.bucket(RustfsContainer.createBucket())
			// Every operation has to stay within the configured prefix
			.keyPrefix("mirror/")
			.build();
	}

}
