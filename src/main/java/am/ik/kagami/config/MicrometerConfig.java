package am.ik.kagami.config;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MicrometerConfig {

	@Bean
	MeterFilter meterFilter() {
		return MeterFilter.deny(id -> {
			String uri = id.getTag("uri");
			return uri != null && (uri.equals("/readyz") || uri.equals("/livez") || uri.startsWith("/actuator")
					|| uri.startsWith("/cloudfoundryapplication") || uri.startsWith("/_static"));
		});
	}

}
