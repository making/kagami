package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties.StorageType;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the settings that follow from {@code kagami.storage.type} so that a single
 * property switches the backend:
 * <ul>
 * <li>{@code local}: the S3 client is not needed, so the Spring Cloud AWS S3
 * auto-configuration is switched off and no region or credentials have to be present. The
 * disk space health indicator and metric are pointed at {@code kagami.storage.path}.</li>
 * <li>{@code s3}: there is no storage directory to watch, so the disk space health
 * indicator and metric are switched off.</li>
 * </ul>
 * Everything is contributed as a default, below every other property source, so explicit
 * configuration always wins. Values that can only be derived from the storage type belong
 * here rather than in {@code application.properties}, which cannot branch on it.
 */
public class StorageEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE_NAME = "kagamiStorageDefaults";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Binder binder = Binder.get(environment);
		StorageType type = binder.bind("kagami.storage.type", StorageType.class).orElse(StorageType.LOCAL);
		Map<String, Object> defaults = new HashMap<>();
		if (type == StorageType.S3) {
			defaults.put("management.health.diskspace.enabled", "false");
			defaults.put("management.metrics.enable.disk", "false");
		}
		else {
			defaults.put("spring.cloud.aws.s3.enabled", "false");
			binder.bind("kagami.storage.path", String.class).ifBound(path -> {
				defaults.put("management.health.diskspace.path", path);
				defaults.put("management.metrics.system.diskspace.paths", ".," + path);
			});
		}
		environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
	}

	@Override
	public int getOrder() {
		// After the configuration files have been loaded, so that kagami.storage.* is
		// visible
		return ConfigDataEnvironmentPostProcessor.ORDER + 1;
	}

}
