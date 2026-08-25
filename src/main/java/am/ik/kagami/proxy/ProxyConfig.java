package am.ik.kagami.proxy;

import am.ik.kagami.KagamiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration that applies the HTTP proxy settings to the outgoing HTTP clients.
 */
@Configuration(proxyBeanMethods = false)
class ProxyConfig {

	private static final Logger logger = LoggerFactory.getLogger(ProxyConfig.class);

	@Bean
	ProxySettings proxySettings(KagamiProperties properties) {
		return ProxySettings.from(properties.proxy());
	}

	/**
	 * Route the requests of every {@code RestClient} built from the auto-configured
	 * builder through the configured proxy. Maven Resolver does not use these clients and
	 * is configured separately per repository.
	 */
	@Bean
	ClientHttpRequestFactoryBuilderCustomizer<ClientHttpRequestFactoryBuilder<?>> proxyClientHttpRequestFactoryBuilderCustomizer(
			ProxySettings proxySettings) {
		return builder -> {
			if (!proxySettings.hasProxy()) {
				return builder;
			}
			if (!(builder instanceof JdkClientHttpRequestFactoryBuilder jdkBuilder)) {
				logger.warn("HTTP proxy settings are not applied to {}", builder.getClass().getName());
				return builder;
			}
			JdkClientHttpRequestFactoryBuilder customized = jdkBuilder
				.withProxySelector(proxySettings.toProxySelector());
			return proxySettings.toAuthenticator()
				.map(authenticator -> customized
					.withHttpClientCustomizer(httpClientBuilder -> httpClientBuilder.authenticator(authenticator)))
				.orElse(customized);
		};
	}

}
