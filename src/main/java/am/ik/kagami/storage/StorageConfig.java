package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Selects the {@link StorageService} backend from {@code kagami.storage.type}. The local
 * file system is the default; {@code s3} switches to Amazon S3 or an S3-compatible object
 * storage whose client is auto-configured by Spring Cloud AWS from
 * {@code spring.cloud.aws.*}.
 *
 * @see StorageEnvironmentPostProcessor
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
		KagamiProperties.Storage.S3 s3 = Objects.requireNonNull(properties.storage().s3(),
				"'kagami.storage.s3.bucket' is required when 'kagami.storage.type' is s3");
		return new S3StorageService(s3, s3Client, s3OutputStreamProvider);
	}

}
