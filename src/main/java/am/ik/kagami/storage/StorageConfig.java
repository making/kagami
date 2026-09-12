package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link StorageService} implementation from {@code kagami.storage.type}. The
 * local file system backend is the default; the S3 backend is chosen when the type is
 * {@code s3} and the {@code S3Client} / {@code S3OutputStreamProvider} are supplied by
 * the Spring Cloud AWS auto-configuration (driven by {@code spring.cloud.aws.*}
 * properties).
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	LocalStorageService localStorageService(KagamiProperties properties) {
		return new LocalStorageService(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	S3StorageService s3StorageService(KagamiProperties properties, S3Client s3Client,
			S3OutputStreamProvider s3OutputStreamProvider) {
		return new S3StorageService(properties, s3Client, s3OutputStreamProvider);
	}

}
