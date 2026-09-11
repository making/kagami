package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties.StorageType;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Derives the settings that follow from {@code kagami.storage.type} so that a single
 * property switches the backend:
 * <ul>
 * <li>{@code local}: the S3 client is not needed, so the Spring Cloud AWS S3
 * auto-configuration is switched off and no region or credentials have to be
 * present.</li>
 * <li>{@code s3}: the disk space health indicator and metric point at
 * {@code kagami.storage.path}, which is meaningless for object storage, so they are
 * switched off (the metric falls back to the working directory).</li>
 * </ul>
 * The derived values sit below system properties, environment variables and test
 * properties, so they can still be overridden explicitly, but above the configuration
 * files that supply the defaults.
 */
public class StorageEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE_NAME = "kagamiStorage";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		StorageType type = Binder.get(environment)
			.bind("kagami.storage.type", StorageType.class)
			.orElse(StorageType.LOCAL);
		Map<String, Object> derived = switch (type) {
			case LOCAL -> Map.of("spring.cloud.aws.s3.enabled", "false");
			case S3 -> Map.of("management.health.diskspace.enabled", "false",
					"management.metrics.system.diskspace.paths", ".");
		};
		MutablePropertySources propertySources = environment.getPropertySources();
		MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, derived);
		if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
			propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
		}
		else {
			propertySources.addFirst(propertySource);
		}
	}

	@Override
	public int getOrder() {
		// After the configuration files have been loaded
		return ConfigDataEnvironmentPostProcessor.ORDER + 1;
	}

}
