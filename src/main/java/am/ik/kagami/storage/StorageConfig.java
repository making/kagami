package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import io.awspring.cloud.s3.S3Template;

/**
 * Selects the {@link StorageService} implementation from {@code kagami.storage.type}.
 * <p>
 * The filesystem backend remains the default, so the matching default is applied when the
 * property is absent. When the type is {@code s3}, the S3 auto-configuration supplies the
 * {@link S3Client} and {@link S3Template} from {@code spring.cloud.aws.*}.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	StorageService localStorageService(KagamiProperties properties) {
		return new LocalStorageService(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	StorageService s3StorageService(S3Client s3Client, S3Template s3Template, KagamiProperties properties) {
		return new S3StorageService(s3Client, s3Template, properties);
	}

}
