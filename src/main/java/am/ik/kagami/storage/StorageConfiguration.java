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
 * wires the infrastructure that only applies to one backend.
 * <p>
 * {@link S3AutoConfiguration} and the diskspace health indicator are excluded from global
 * auto-configuration so that the default local backend needs no AWS region, credentials
 * or endpoint. They are imported here only for the backend that needs them. For S3 the
 * diskspace health indicator is not imported and the diskspace metric is replaced by a
 * no-op, since neither is meaningful without a local filesystem.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

	/**
	 * Local file system storage; the default when {@code kagami.storage.type} is unset.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "local", matchIfMissing = true)
	@Import(DiskSpaceHealthContributorAutoConfiguration.class)
	static class Local {

		@Bean
		StorageService localStorageService(KagamiProperties properties) {
			return new LocalStorageService(properties);
		}

	}

	/**
	 * S3 storage. Spring Cloud AWS S3 support is imported here so that it is only active
	 * when this backend is selected.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")
	@Import(S3AutoConfiguration.class)
	static class S3 {

		@Bean
		StorageService s3StorageService(S3Client s3Client, KagamiProperties properties) {
			return new S3StorageService(s3Client, properties);
		}

		@Bean
		DiskSpaceMetricsBinder diskSpaceMetricsBinder() {
			return new NoOpDiskSpaceMetricsBinder();
		}

	}

	static final class NoOpDiskSpaceMetricsBinder extends DiskSpaceMetricsBinder {

		NoOpDiskSpaceMetricsBinder() {
			super(List.of(new File(".")), Tags.empty());
		}

		@Override
		public void bindTo(MeterRegistry registry) {
		}

	}

}
