package am.ik.kagami.token.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * A {@link BearerTokenResolver} that also accepts HTTP Basic authentication whose
 * password part is a JWT token. This allows the standard Maven {@code settings.xml}
 * {@code <server>} configuration with {@code <username>} / {@code <password>} to be used
 * for accessing private repositories. The username part is ignored and the password part
 * is treated as the bearer token.
 */
public class BasicToBearerTokenResolver implements BearerTokenResolver {

	private static final String BASIC_PREFIX = "Basic ";

	private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();

	@Override
	public String resolve(HttpServletRequest request) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null && authorization.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
			return resolveFromBasic(authorization.substring(BASIC_PREFIX.length()));
		}
		return this.delegate.resolve(request);
	}

	private String resolveFromBasic(String encodedCredentials) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(encodedCredentials.trim());
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
		String credentials = new String(decoded, StandardCharsets.UTF_8);
		int delimiter = credentials.indexOf(':');
		if (delimiter < 0) {
			return null;
		}
		String token = credentials.substring(delimiter + 1);
		return token.isEmpty() ? null : token;
	}

}
