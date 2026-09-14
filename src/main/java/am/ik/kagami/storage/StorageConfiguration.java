package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.File;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.application.DiskSpaceHealthContributorAutoConfiguration;
import org.springframework.boot.micrometer.metrics.system.DiskSpaceMetricsBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Selects the {@link StorageService} implementation from {@code kagami.storage.type} and
 * tunes the infrastructure that only makes sense for one backend. Both branches are
 * evaluated late, so values from any property source (including test-only ones) are
 * honored.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

	/**
	 * S3 storage. The Spring Cloud AWS S3 support (excluded from the global
	 * auto-configuration) is imported here so that the default local storage works
	 * without region, credentials or an endpoint. The diskspace health indicator and
	 * metric are meaningless without a local filesystem, so they stay disabled here: the
	 * health contributor is simply not imported and the metric binder is replaced with a
	 * no-op.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	@Import(S3AutoConfiguration.class)
	static class S3 {

		@Bean
		public StorageService s3StorageService(S3Client s3Client, KagamiProperties properties) {
			return new S3StorageService(s3Client, properties);
		}

		@Bean
		public DiskSpaceMetricsBinder diskSpaceMetricsBinder() {
			return new NoOpDiskSpaceMetricsBinder();
		}

	}

	/**
	 * Local filesystem storage (default).
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	@Import(DiskSpaceHealthContributorAutoConfiguration.class)
	static class Local {

		@Bean
		public StorageService localStorageService(KagamiProperties properties) {
			return new LocalStorageService(properties);
		}

	}

	static final class NoOpDiskSpaceMetricsBinder extends DiskSpaceMetricsBinder {

		private NoOpDiskSpaceMetricsBinder() {
			super(List.of(new File(".")), Tags.empty());
		}

		@Override
		public void bindTo(MeterRegistry registry) {
		}

	}

}
