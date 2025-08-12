package am.ik.kagami.config;

import com.samskivert.mustache.Mustache;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.web.servlet.view.MustacheViewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

@Configuration(proxyBeanMethods = false)
class MustacheConfig {

	@Bean
	InitializingBean mustacheViewResolverCustomizer(MustacheViewResolver mustacheViewResolver,
			ResourceUrlProvider urlProvider) {
		return () -> {
			Map<String, Object> attributesMap = new LinkedHashMap<>();
			attributesMap.put("src", (Mustache.Lambda) (frag, out) -> {
				String url = frag.execute();
				String resourceUrl = urlProvider.getForLookupPath(frag.execute());
				if (StringUtils.hasLength(resourceUrl)) {
					out.write(resourceUrl);
				}
				else {
					out.write(url);
				}
			});
			mustacheViewResolver.setAttributesMap(attributesMap);
		};
	}

}
