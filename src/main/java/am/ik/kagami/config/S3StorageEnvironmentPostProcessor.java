package am.ik.kagami.config;

import am.ik.kagami.KagamiProperties.StorageType;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Tunes the AWS and Actuator auto-configuration based on the storage type before the
 * configuration properties are bound.
 * <p>
 * The Spring Cloud AWS S3 auto-configuration is off by default (see
 * {@code application.properties}); it is turned on only when the storage type is
 * {@code s3}, so that no {@code S3Client} (which requires a region and credentials) is
 * created for the local backend. When the type is {@code s3}, the local-filesystem based
 * disk space health indicator and the disk space metrics (which are wired to
 * {@code kagami.storage.path}) are also disabled.
 */
public class S3StorageEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String type = environment.getProperty("kagami.storage.type");
		if (type == null || !type.equalsIgnoreCase(StorageType.S3.name())) {
			return;
		}
		Map<String, Object> properties = new HashMap<>();
		// Enable the S3 auto-configuration so that an S3Client is created
		properties.put("spring.cloud.aws.s3.enabled", "true");
		// Disable the disk space health indicator
		properties.put("management.health.diskspace.enabled", "false");
		// Disable the disk space metric (meters "disk.free" / "disk.total")
		properties.put("management.metrics.enable.disk", "false");
		environment.getPropertySources().addFirst(new MapPropertySource("kagamiStorage", properties));
	}

	@Override
	public int getOrder() {
		// Run after the config data (application.properties) is loaded so that
		// kagami.storage.type is available, and before the configuration properties are
		// bound so the overrides win
		return ConfigDataEnvironmentPostProcessor.ORDER + 10;
	}

}
