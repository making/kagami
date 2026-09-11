package am.ik.kagami.storage;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StorageEnvironmentPostProcessorTest {

	final StorageEnvironmentPostProcessor postProcessor = new StorageEnvironmentPostProcessor();

	final SpringApplication application = new SpringApplication();

	@Test
	void localStorageDisablesTheS3Client() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.addLast(new MapPropertySource("application",
					Map.of("kagami.storage.type", "local", "management.health.diskspace.enabled", "true",
							"management.metrics.system.diskspace.paths", ".,/x")));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("spring.cloud.aws.s3.enabled")).isEqualTo("false");
		assertThat(environment.getProperty("management.health.diskspace.enabled")).isEqualTo("true");
		assertThat(environment.getProperty("management.metrics.system.diskspace.paths")).isEqualTo(".,/x");
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
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.addLast(new MapPropertySource("application",
					Map.of("kagami.storage.type", "S3", "management.health.diskspace.enabled", "true",
							"management.metrics.system.diskspace.paths", ".,/x")));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("spring.cloud.aws.s3.enabled")).isNull();
		assertThat(environment.getProperty("management.health.diskspace.enabled")).isEqualTo("false");
		assertThat(environment.getProperty("management.metrics.system.diskspace.paths")).isEqualTo(".");
	}

	@Test
	void derivedPropertiesSitBelowSystemPropertiesAndEnvironmentVariables() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.addLast(new MapPropertySource("application", Map.of("kagami.storage.type", "s3")));
		environment.getPropertySources()
			.addFirst(new MapPropertySource("commandLine", Map.of("management.health.diskspace.enabled", "true")));

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getProperty("management.health.diskspace.enabled")).isEqualTo("true");
		List<String> names = environment.getPropertySources().stream().map(PropertySource::getName).toList();
		assertThat(names.indexOf(StorageEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
			.isEqualTo(names.indexOf(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME) + 1);
	}

	@Test
	void withoutSystemEnvironmentTheDerivedPropertiesComeFirst() {
		MockEnvironment environment = new MockEnvironment().withProperty("kagami.storage.type", "s3");

		this.postProcessor.postProcessEnvironment(environment, this.application);

		assertThat(environment.getPropertySources().iterator().next().getName())
			.isEqualTo(StorageEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
		assertThat(environment.getProperty("management.health.diskspace.enabled")).isEqualTo("false");
	}

}
