package am.ik.kagami.config;

import java.time.InstantSource;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

/**
 * Configuration class for Kagami application
 */
@Configuration(proxyBeanMethods = false)
class AppConfig {

	@Bean
	InstantSource instantSource() {
		return InstantSource.system();
	}

	@Bean
	RestClientCustomizer restClientCustomizer(LogbookClientHttpRequestInterceptor logbookClientHttpRequestInterceptor) {
		return builder -> builder.requestInterceptor(logbookClientHttpRequestInterceptor);
	}

}