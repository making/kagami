package am.ik.kagami.config;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.token.web.BasicToJwtAuthorizationExchangeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole;
import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, KagamiProperties properties) throws Exception {
		return http.authorizeHttpRequests(authz -> {
			properties.repositories().forEach((repositoryId, repository) -> {
				if (repository.isPrivate()) {
					authz.requestMatchers(GET, "/artifacts/%s/**".formatted(repositoryId))
						.access(anyOf(hasScope("artifacts:read"), hasRole("USER")));
					authz.requestMatchers(DELETE, "/artifacts/%s/**".formatted(repositoryId))
						.access(anyOf(hasScope("artifacts:delete"), hasRole("USER")));
				}
			});
			authz.anyRequest().permitAll();
		}).oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
		}))
			.csrf(csrf -> csrf.disable())
			.addFilterBefore(new BasicToJwtAuthorizationExchangeFilter(), BearerTokenAuthenticationFilter.class)
			.build();
	}

}
