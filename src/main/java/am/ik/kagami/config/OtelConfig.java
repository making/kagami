package am.ik.kagami.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.contrib.sampler.RuleBasedRoutingSampler;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OtelConfig {

	@Bean
	static BeanPostProcessor filteringSpanExporterRegistrar() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
				if (bean instanceof Sampler) {
					AttributeKey<String> uri = AttributeKey.stringKey("uri");
					return RuleBasedRoutingSampler.builder(SpanKind.SERVER, (Sampler) bean)
						.drop(uri, "^/readyz")
						.drop(uri, "^/livez")
						.drop(uri, "^/actuator")
						.drop(uri, "^/cloudfoundryapplication")
						.drop(uri, "^/_static")
						.build();
				}
				return bean;
			}
		};
	}

}