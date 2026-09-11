package am.ik.kagami.storage;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Adapts the auto-configuration to the selected storage backend based on
 * {@code kagami.storage.type}:
 * <ul>
 * <li>the S3 client auto-configuration is enabled only for {@code s3}, so that a local
 * deployment neither needs a region nor credentials,</li>
 * <li>the disk space health indicator and metric are disabled for {@code s3}, because
 * they monitor {@code kagami.storage.path}, which the S3 backend does not use.</li>
 * </ul>
 */
public class StorageEnvironmentPostProcessor implements EnvironmentPostProcessor {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String type = environment.getProperty("kagami.storage.type");
		boolean s3 = type != null && type.equalsIgnoreCase("s3");
		Map<String, Object> properties = new HashMap<>();
		properties.put("spring.cloud.aws.s3.enabled", s3);
		properties.put("management.health.diskspace.enabled", !s3);
		properties.put("management.metrics.enable.disk", !s3);
		environment.getPropertySources().addFirst(new MapPropertySource("kagami-storage", properties));
	}

}
