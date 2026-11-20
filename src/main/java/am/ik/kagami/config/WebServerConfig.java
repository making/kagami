package am.ik.kagami.config;

import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web server settings that Spring Boot does not expose as a property.
 */
@Configuration(proxyBeanMethods = false)
class WebServerConfig {

	/**
	 * Keep a percent-encoded slash ({@code %2F}) in the request URI instead of rejecting
	 * or decoding it. The npm registry addresses a scoped package as
	 * {@code @scope%2Fname}, and the artifact router decodes the path itself so that the
	 * format handler receives {@code @scope/name}. Spring Boot 4.2 has no
	 * {@code server.tomcat.encoded-solidus-handling-policy} property, so the connector
	 * setting is applied here.
	 */
	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSolidusHandlingCustomizer() {
		return factory -> factory.addConnectorCustomizers(
				connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue()));
	}

}
