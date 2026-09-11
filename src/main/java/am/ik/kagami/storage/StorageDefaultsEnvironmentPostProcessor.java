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
 * Contributes the defaults that can only be derived from the selected storage backend.
 * <p>
 * The AWS S3 client is built eagerly and fails when no region can be resolved, so it must
 * not be auto-configured at all unless the S3 backend is in use. Conversely the disk
 * space health indicator and metric are meaningless without a local storage directory.
 * Every value is contributed as a default, so explicit configuration still wins.
 */
public class StorageDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final String PROPERTY_SOURCE_NAME = "kagamiStorageDefaults";

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
		// Runs once the configuration files have been loaded so that the storage
		// properties can be read
		return ConfigDataEnvironmentPostProcessor.ORDER + 1;
	}

}
