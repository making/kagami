package am.ik.kagami.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

/**
 * Configuration class for Kagami application
 */
@Configuration(proxyBeanMethods = false)
class AppConfig {

	@Bean
	RestClientCustomizer restClientCustomizer(Logbook logbook) {
		return builder -> builder.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook));
	}

}