package am.ik.kagami;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Same scenario as {@link KagamiIntegrationTest} but with the artifacts mirrored into the
 * S3-backed storage served by the rustfs test server.
 */
@Import(MockConfig.class)
class S3StorageIntegrationTest extends KagamiIntegrationTest {

	@DynamicPropertySource
	static void configureS3Storage(DynamicPropertyRegistry registry) {
		Rustfs.registerProperties(registry);
	}

}
