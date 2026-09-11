package am.ik.kagami;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Containers that stand in for external services. With {@code kagami.storage.type=s3} a
 * RustFS container provides the S3-compatible storage and the application is pointed at
 * it through {@code spring.cloud.aws.*}; with the default local storage nothing is
 * started.
 * <p>
 * {@code kagami.storage.type} itself has to be a static test property because it is
 * evaluated while the environment is prepared, before dynamic properties exist.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String BUCKET = "kagami";

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	RustFsContainer rustFsContainer() {
		return new RustFsContainer().withBucket(BUCKET);
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	DynamicPropertyRegistrar rustFsDynamicPropertyRegistrar(RustFsContainer rustFs) {
		return registry -> {
			registry.add("spring.cloud.aws.s3.endpoint", rustFs::endpoint);
			registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> true);
			registry.add("spring.cloud.aws.region.static", () -> RustFsContainer.REGION);
			registry.add("spring.cloud.aws.credentials.access-key", () -> RustFsContainer.ACCESS_KEY);
			registry.add("spring.cloud.aws.credentials.secret-key", () -> RustFsContainer.SECRET_KEY);
			registry.add("kagami.storage.s3.bucket", () -> BUCKET);
		};
	}

}
