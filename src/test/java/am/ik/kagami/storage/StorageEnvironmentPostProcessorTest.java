package am.ik.kagami.storage;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StorageEnvironmentPostProcessorTest {

	final StorageEnvironmentPostProcessor postProcessor = new StorageEnvironmentPostProcessor();

	final SpringApplication application = new SpringApplication();

	@Test
	void localStorageDisablesTheS3ClientAndWatchesTheStorageDirectory() {
		StandardEnvironment environment = environment(
				Map.of("kagami.storage.type", "local", "kagami.storage.path", "/var/kagami/storage"));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("spring.cloud.aws.s3.enabled")).isEqualTo("false");
		assertThat(environment.getProperty("management.health.diskspace.path")).isEqualTo("/var/kagami/storage");
		assertThat(environment.getProperty("management.metrics.system.diskspace.paths"))
			.isEqualTo(".,/var/kagami/storage");
		assertThat(environment.getProperty("management.health.diskspace.enabled")).isNull();
		assertThat(environment.getProperty("management.metrics.enable.disk")).isNull();
	}

	@Test
	void localStorageIsTheDefault() {
		StandardEnvironment environment = new StandardEnvironment();

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("spring.cloud.aws.s3.enabled")).isEqualTo("false");
		assertThat(environment.getProperty("management.health.diskspace.enabled")).isNull();
	}

	@Test
	void s3StorageDisablesTheDiskSpaceHealthIndicatorAndMetric() {
		StandardEnvironment environment = environment(
				Map.of("kagami.storage.type", "S3", "kagami.storage.path", "/var/kagami/storage"));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("management.health.diskspace.enabled")).isEqualTo("false");
		assertThat(environment.getProperty("management.metrics.enable.disk")).isEqualTo("false");
		// The unused storage directory is not watched
		assertThat(environment.getProperty("management.health.diskspace.path")).isNull();
		assertThat(environment.getProperty("management.metrics.system.diskspace.paths")).isNull();
		assertThat(environment.getProperty("spring.cloud.aws.s3.enabled")).isNull();
	}

	@Test
	void derivedValuesAreDefaultsThatExplicitConfigurationOverrides() {
		StandardEnvironment environment = environment(Map.of("kagami.storage.type", "s3"));
		environment.getPropertySources()
			.addFirst(new MapPropertySource("commandLine", Map.of("management.health.diskspace.enabled", "true")));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("management.health.diskspace.enabled")).isEqualTo("true");
		assertThat(environment.getProperty("management.metrics.enable.disk")).isEqualTo("false");
	}

	@Test
	void derivedValuesSitBelowEveryOtherPropertySource() {
		StandardEnvironment environment = environment(Map.of("kagami.storage.type", "s3"));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		List<String> names = environment.getPropertySources().stream().map(PropertySource::getName).toList();
		assertThat(names).last().isEqualTo(StorageEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
	}

	private static StandardEnvironment environment(Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addLast(new MapPropertySource("application", properties));
		return environment;
	}

}
