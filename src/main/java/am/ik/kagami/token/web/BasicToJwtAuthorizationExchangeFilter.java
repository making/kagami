package am.ik.kagami.token.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

public class BasicToJwtAuthorizationExchangeFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws IOException, ServletException {
		// Check if the request has Basic Authorization header
		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authHeader != null && authHeader.startsWith("Basic ")) {
			String base64Credentials = authHeader.substring("Basic ".length()).trim();
			String credentials = new String(Base64.getDecoder().decode(base64Credentials));
			String[] parts = credentials.split(":", 2);
			if (parts.length == 2) {
				String jwt = parts[1];
				String newAuthorization = "Bearer " + jwt;
				HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
					@Override
					public String getHeader(String name) {
						if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
							return newAuthorization;
						}
						return super.getHeader(name);
					}
				};
				filterChain.doFilter(wrappedRequest, response);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

}
