package am.ik.kagami.config;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.AuthenticationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole;
import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, KagamiProperties properties) throws Exception {
		HttpSecurity security = http
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
					.requestMatchers("/", "/login", "/logout", "/*.css", "/assets/**", "/error", "/.well-known/**", "/openid/v1/jwks").permitAll()
					.requestMatchers("/me").authenticated()
					.anyRequest().hasRole("USER");
			})
				// @formatter:on
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
			}))
			.csrf(csrf -> csrf.ignoringRequestMatchers("/artifacts/**", "/token"))
			.logout(logout -> logout.logoutUrl("/logout")
				.logoutSuccessUrl("/login?logout")
				.deleteCookies("JSESSIONID"));
		AuthenticationType authenticationType = properties.authentication().type();
		switch (authenticationType) {
			case SIMPLE -> security.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
				.rememberMe(Customizer.withDefaults());
			case OIDC -> security.oauth2Login(oauth2 -> oauth2.loginPage("/login")
				.defaultSuccessUrl("/", true)
				.userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(grantedAuthoritiesMapper(properties))));
			case null, default ->
				throw new IllegalStateException("Unsupported authentication type: " + authenticationType);
		}
		return security.build();
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

	GrantedAuthoritiesMapper grantedAuthoritiesMapper(KagamiProperties props) {
		return authorities -> {
			List<GrantedAuthority> authorityList = new ArrayList<>();
			for (GrantedAuthority grantedAuthority : authorities) {
				if (grantedAuthority instanceof OidcUserAuthority oidcUserAuthority) {
					String userName = (String) oidcUserAuthority.getAttributes()
						.get(oidcUserAuthority.getUserNameAttributeName());
					for (Pattern allowedNamePattern : props.authentication().allowedNamePatterns()) {
						if (allowedNamePattern.matcher(userName).matches()) {
							logger.info("Allowed name pattern: {} => {}", userName, allowedNamePattern);
							authorityList.add(new SimpleGrantedAuthority("ROLE_USER"));
							break;
						}
					}
					if (authorityList.isEmpty()) {
						logger.info("User {} does not match any allowed name patterns", userName);
					}
					break;
				}
			}
			return authorityList;
		};
	}

}
