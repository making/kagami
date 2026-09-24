package am.ik.kagami.config;

import am.ik.kagami.rbac.RbacBuiltins;
import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.AuthenticationType;
import am.ik.kagami.rbac.OidcUserAuthoritiesMapper;
import am.ik.kagami.rbac.RbacService;
import am.ik.kagami.token.web.BasicToBearerTokenResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
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
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;
import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, KagamiProperties properties, RbacService rbacService)
			throws Exception {
		AuthenticationEntryPoint artifactsEntryPoint = artifactsAuthenticationEntryPoint();
		RequestMatcher artifactApi = PathPatternRequestMatcher.withDefaults().matcher("/artifacts/**");
		RequestMatcher garbageCollectionApi = PathPatternRequestMatcher.withDefaults().matcher("/artifacts/*/gc");
		RequestMatcher tokenApi = PathPatternRequestMatcher.withDefaults().matcher("/token");
		RequestMatcher bearerAuthentication = request -> {
			String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
			return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
		};
		HttpSecurity security = http
		// @formatter:off
			.authorizeHttpRequests(authz -> {
				authz.requestMatchers("/admin", "/app/admin/**")
					.access(anyOf(hasScope(RbacBuiltins.ADMIN_AUTHORITY), hasAuthority(RbacBuiltins.ADMIN_AUTHORITY)));
				properties.repositories().forEach((repositoryId, repository) -> {
					String garbageCollectionPath = "/artifacts/%s/gc".formatted(repositoryId);
					authz.requestMatchers(GET, garbageCollectionPath).access(anyOf(hasScope(RbacBuiltins.ADMIN_AUTHORITY), hasAuthority(RbacBuiltins.ADMIN_AUTHORITY)));
					authz.requestMatchers(HEAD, garbageCollectionPath).access(anyOf(hasScope(RbacBuiltins.ADMIN_AUTHORITY), hasAuthority(RbacBuiltins.ADMIN_AUTHORITY)));
					authz.requestMatchers(POST, garbageCollectionPath).access(anyOf(hasScope(RbacBuiltins.ADMIN_AUTHORITY), hasAuthority(RbacBuiltins.ADMIN_AUTHORITY)));
					authz.requestMatchers(DELETE, garbageCollectionPath).access(anyOf(hasScope(RbacBuiltins.ADMIN_AUTHORITY), hasAuthority(RbacBuiltins.ADMIN_AUTHORITY)));
					if (repository.isPrivate()) {
						authz.requestMatchers(GET, "/artifacts/%s/**".formatted(repositoryId)).access(anyOf(hasScope(RbacBuiltins.READ_AUTHORITY), hasAuthority(RbacBuiltins.READ_AUTHORITY)));
						authz.requestMatchers(HEAD, "/artifacts/%s/**".formatted(repositoryId)).access(anyOf(hasScope(RbacBuiltins.READ_AUTHORITY), hasAuthority(RbacBuiltins.READ_AUTHORITY)));
					}
					else {
						authz.requestMatchers(GET, "/artifacts/%s/**".formatted(repositoryId)).permitAll();
						authz.requestMatchers(HEAD, "/artifacts/%s/**".formatted(repositoryId)).permitAll();
					}
					authz.requestMatchers(DELETE, "/artifacts/%s/**".formatted(repositoryId)).access(anyOf(hasScope(RbacBuiltins.DELETE_AUTHORITY), hasAuthority(RbacBuiltins.DELETE_AUTHORITY)));
				});
				authz.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
					.requestMatchers("/login", "/logout", "/css/**", "/js/**", "/fonts/**", "/favicon.svg", "/error", "/.well-known/**", "/openid/v1/jwks").permitAll()
					.anyRequest().authenticated();
			})
				// @formatter:on
			.oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(new BasicToBearerTokenResolver())
				.authenticationEntryPoint(artifactsEntryPoint)
				.jwt(jwt -> {
				}))
			.exceptionHandling(exception -> exception
				.defaultAuthenticationEntryPointFor(artifactsEntryPoint,
						PathPatternRequestMatcher.withDefaults().matcher("/artifacts/**"))
				.defaultAuthenticationEntryPointFor(htmxAuthenticationEntryPoint(),
						new RequestHeaderRequestMatcher("HX-Request", "true"))
				.accessDeniedHandler(errorPageAccessDeniedHandler()))
			.csrf(csrf -> csrf.ignoringRequestMatchers(new AndRequestMatcher(tokenApi, bearerAuthentication))
				.ignoringRequestMatchers(
						new AndRequestMatcher(artifactApi, new NegatedRequestMatcher(garbageCollectionApi)))
				.ignoringRequestMatchers(new AndRequestMatcher(garbageCollectionApi, bearerAuthentication)))
			.logout(logout -> logout.logoutUrl("/logout")
				.logoutSuccessUrl("/login?logout")
				.deleteCookies("JSESSIONID"));
		AuthenticationType authenticationType = properties.authentication().type();
		switch (authenticationType) {
			case SIMPLE -> security.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
				.rememberMe(Customizer.withDefaults());
			case OIDC -> security.oauth2Login(oauth2 -> oauth2.loginPage("/login")
				.defaultSuccessUrl("/", true)
				.userInfoEndpoint(userInfo -> userInfo
					.userAuthoritiesMapper(new OidcUserAuthoritiesMapper(properties, rbacService))));
			case null, default ->
				throw new IllegalStateException("Unsupported authentication type: " + authenticationType);
		}
		return security.build();
	}

	/**
	 * Entry point for artifact endpoints that advertises the Basic authentication scheme
	 * in addition to Bearer so that Maven clients configured with the standard
	 * {@code <username>} / {@code <password>} server settings respond to the 401
	 * challenge with Basic credentials, which are then resolved as a bearer token by
	 * {@link BasicToBearerTokenResolver}. The Basic scheme is not advertised to web
	 * browsers because they would pop up a native credential dialog on a 401 response
	 * (e.g. when the login session has expired).
	 */
	private static AuthenticationEntryPoint artifactsAuthenticationEntryPoint() {
		BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
		return (request, response, authException) -> {
			bearerEntryPoint.commence(request, response, authException);
			if (!isBrowserRequest(request)) {
				response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Kagami\"");
			}
		};
	}

	/**
	 * Determines whether the request originates from a web browser. Browsers send
	 * {@code Sec-Fetch-*} headers on every request and a {@code User-Agent} starting with
	 * {@code Mozilla}, while Maven and Gradle clients send neither.
	 */
	private static boolean isBrowserRequest(HttpServletRequest request) {
		if (request.getHeader("Sec-Fetch-Mode") != null) {
			return true;
		}
		String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
		return userAgent != null && userAgent.startsWith("Mozilla");
	}

	/**
	 * Authentication entry point for htmx requests: the browser follows the usual
	 * redirect to the login page inside the fetch request and htmx would swap the login
	 * page into the fragment target, so an {@code HX-Redirect} is sent instead to force a
	 * full-page navigation.
	 */
	private static AuthenticationEntryPoint htmxAuthenticationEntryPoint() {
		return (request, response, authException) -> {
			response.setStatus(401);
			response.setHeader("HX-Redirect", "/login");
		};
	}

	/**
	 * Access denied handler that renders the error page; htmx requests get an
	 * {@code HX-Redirect} to the error page instead. Machine endpoints under
	 * {@code /artifacts/**} keep the default handler behaviour.
	 */
	private static AccessDeniedHandler errorPageAccessDeniedHandler() {
		AccessDeniedHandler bearerHandler = new BearerTokenAccessDeniedHandler();
		AccessDeniedHandler defaultHandler = new AccessDeniedHandlerImpl();
		return (request, response, accessDeniedException) -> {
			if (request.getRequestURI().startsWith("/artifacts/")) {
				if (request.getUserPrincipal() instanceof JwtAuthenticationToken) {
					bearerHandler.handle(request, response, accessDeniedException);
				}
				else {
					defaultHandler.handle(request, response, accessDeniedException);
				}
				return;
			}
			response.setStatus(403);
			if ("true".equals(request.getHeader("HX-Request"))) {
				response.setHeader("HX-Redirect", "/error?status=403");
				return;
			}
			request.getRequestDispatcher("/error").forward(request, response);
		};
	}

	@Bean
	UserDetailsService userDetailsService(SecurityProperties properties, RbacService rbacService) {
		SecurityProperties.User user = properties.getUser();
		// The simple auth user gets the authorities of its RBAC groups instead of the
		// static spring.security.user.roles; this covers form login and remember-me alike
		UserDetails userDetails = User.withUsername(user.getName())
			.password(user.getPassword())
			.authorities(rbacService.authoritiesFor(user.getName(), List.of()))
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
