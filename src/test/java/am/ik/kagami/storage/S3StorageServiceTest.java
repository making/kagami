package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.RustfsContainer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the storage contract against the S3 backend backed by rustfs.
 */
class S3StorageServiceTest extends StorageServiceContractTest {

	private static S3Client s3Client;

	@BeforeAll
	static void setUp() {
		RustfsContainer.ensureBucket();
		s3Client = RustfsContainer.client();
	}

	@AfterAll
	static void tearDown() {
		s3Client.close();
	}

	@BeforeEach
	void clear() {
		RustfsContainer.clearBucket();
	}

	@Override
	protected StorageService storageService() {
		return new S3StorageService(s3Client, new KagamiProperties.Storage(KagamiProperties.StorageType.S3, null,
				new KagamiProperties.Storage.S3(RustfsContainer.BUCKET, null)));
	}

	@Test
	void keyPrefixIsPrependedToObjectKeys() throws IOException {
		StorageService storage = new S3StorageService(s3Client,
				new KagamiProperties.Storage(KagamiProperties.StorageType.S3, null,
						new KagamiProperties.Storage.S3(RustfsContainer.BUCKET, "/kagami/")));
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.jar");
		try (InputStream inputStream = new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))) {
			storage.store(location, inputStream);
		}

		assertThat(s3Client.listObjectsV2(request -> request.bucket(RustfsContainer.BUCKET).prefix("kagami/test-repo/"))
			.contents()).extracting(S3Object::key).containsExactly("kagami/test-repo/org/example/lib/1.0/lib-1.0.jar");
	}

}
