package am.ik.kagami.config;

import am.ik.kagami.KagamiProperties;
import java.util.Map;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole;
import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, KagamiProperties properties) throws Exception {
		return http
		// @formatter:off
			.authorizeHttpRequests(authz -> {
				properties.repositories().forEach((repositoryId, repository) -> {
					if (repository.isPrivate()) {
						authz.requestMatchers(GET, "/artifacts/%s/**".formatted(repositoryId)).access(anyOf(hasScope("artifacts:read"), hasRole("USER")));
						authz.requestMatchers(HEAD, "/artifacts/%s/**".formatted(repositoryId)).access(anyOf(hasScope("artifacts:read"), hasRole("USER")));
					}
					else {
						authz.requestMatchers(GET, "/artifacts/%s/**".formatted(repositoryId)).permitAll();
						authz.requestMatchers(HEAD, "/artifacts/%s/**".formatted(repositoryId)).permitAll();
					}
					authz.requestMatchers(DELETE, "/artifacts/%s/**".formatted(repositoryId)).access(anyOf(hasScope("artifacts:delete"), hasRole("USER")));
				});
				authz.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
					.requestMatchers("/login", "/*.css", "/error").permitAll()
					.requestMatchers(POST, "/token").hasRole("USER")
					.requestMatchers("/me").hasRole("USER")
					.anyRequest().authenticated();
			})
				// @formatter:on
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
			}))
			.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
			.logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").deleteCookies("JSESSIONID"))
			.rememberMe(Customizer.withDefaults())
			.csrf(csrf -> csrf.ignoringRequestMatchers("/artifacts/**", "/token"))
			.build();
	}

	@Bean
	UserDetailsService userDetailsService(SecurityProperties properties) {
		SecurityProperties.User user = properties.getUser();
		UserDetails userDetails = User.withUsername(user.getName())
			.password(user.getPassword())
			.roles(user.getRoles().toArray(String[]::new))
			.build();
		return new InMemoryUserDetailsManager(userDetails);
	}

	@SuppressWarnings("deprecation")
	@Bean
	PasswordEncoder passwordEncoder() {
		String idForEncode = "bcrypt";
		return new DelegatingPasswordEncoder(idForEncode,
				Map.of(idForEncode, new BCryptPasswordEncoder(), "noop", NoOpPasswordEncoder.getInstance()));
	}

}
