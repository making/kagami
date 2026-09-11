package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Selects the {@link StorageService} implementation from {@code kagami.storage.type}.
 * <p>
 * The local file system backend is the default; the S3 backend is only instantiated when
 * the type is {@code s3}, so that the S3 client and its auto-configuration stay out of
 * the way of a local deployment.
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	StorageService localStorageService(KagamiProperties properties) {
		return new LocalStorageService(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	StorageService s3StorageService(S3Client s3Client, KagamiProperties properties) {
		return new S3StorageService(s3Client, properties.storage());
	}

}
