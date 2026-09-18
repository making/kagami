package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.application.DiskSpaceHealthIndicator;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.micrometer.metrics.system.DiskSpaceMetricsBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Selects the {@link StorageService} implementation from {@code kagami.storage.type}:
 * {@code LOCAL} (default) or {@code S3}.
 * <p>
 * This configuration also owns the diskspace health indicator and diskspace metrics
 * because they only make sense for the local file system backend: for {@code LOCAL} they
 * are registered against {@code kagami.storage.path}, for {@code S3} they are absent.
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	LocalStorageService localStorageService(KagamiProperties properties) {
		return new LocalStorageService(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	S3StorageService s3StorageService(S3Client s3Client, KagamiProperties properties) {
		KagamiProperties.Storage.S3 s3 = properties.storage().s3();
		if (s3 == null || !StringUtils.hasText(s3.bucket())) {
			throw new IllegalStateException("'kagami.storage.s3.bucket' is required when kagami.storage.type=s3");
		}
		return new S3StorageService(s3Client, s3.bucket(), s3.keyPrefix());
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	S3Client s3Client(Environment environment) {
		Binder binder = Binder.get(environment);
		S3ClientBuilder builder = S3Client.builder();
		// Region, credentials, endpoint and path-style access all come from the
		// spring.cloud.aws.* properties, falling back to the AWS SDK default chains
		var staticRegion = binder.bind("spring.cloud.aws.region.static", String.class);
		if (staticRegion.isBound()) {
			builder.region(Region.of(staticRegion.get().trim()));
		}
		else {
			builder.region(new DefaultAwsRegionProviderChain().getRegion());
		}
		binder.bind("spring.cloud.aws.credentials.access-key", String.class)
			.ifBound(accessKey -> binder.bind("spring.cloud.aws.credentials.secret-key", String.class)
				.ifBound(secretKey -> builder.credentialsProvider(
						StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))));
		binder.bind("spring.cloud.aws.s3.endpoint", String.class)
			.ifBound(endpoint -> builder.endpointOverride(URI.create(endpoint.trim())));
		binder.bind("spring.cloud.aws.s3.path-style-access-enabled", Boolean.class)
			.ifBound(pathStyle -> builder.forcePathStyle(pathStyle));
		return builder.build();
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	DiskSpaceHealthIndicator diskSpaceHealthIndicator(KagamiProperties properties) {
		return new DiskSpaceHealthIndicator(Path.of(properties.storage().path()).toFile(), DataSize.ofMegabytes(10));
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	DiskSpaceMetricsBinder diskSpaceMetricsBinder(KagamiProperties properties) {
		return new DiskSpaceMetricsBinder(List.of(Path.of(properties.storage().path()).toFile()), List.of());
	}

	@Bean
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	MeterRegistryCustomizer<MeterRegistry> diskspaceMetricsDisabler() {
		return registry -> registry.config()
			.meterFilter(MeterFilter.deny(id -> id.getName().startsWith("system.diskspace")));
	}

}
