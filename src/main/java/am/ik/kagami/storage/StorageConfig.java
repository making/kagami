package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Selects the storage backend from {@code kagami.storage.type}.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	public StorageService localStorageService(KagamiProperties properties) {
		return new LocalStorageService(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	public StorageService s3StorageService(KagamiProperties properties, S3Client s3Client,
			S3OutputStreamProvider outputStreamProvider) {
		KagamiProperties.Storage.S3 s3 = properties.storage().s3();
		return S3StorageService.builder()
			.s3Client(s3Client)
			.outputStreamProvider(outputStreamProvider)
			.bucket(s3.requiredBucket())
			.keyPrefix(s3.keyPrefix())
			.build();
	}

}
