package am.ik.kagami.error.web;

import java.net.CookieManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Access-denied handling through the real servlet container: a user without the admin
 * authority hitting {@code /admin} must be routed to the error page and receive the fully
 * rendered 403 page, not a 500 or a truncated body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.security.user.name=test-user", "spring.security.user.password={noop}test-pass" })
class ErrorPageAccessDeniedIntegrationTest {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@LocalServerPort
	int port;

	@Test
	void accessDeniedRendersFullErrorPage() throws Exception {
		// A cookie-aware client so that the login session spans the requests
		HttpClient httpClient = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
		RestClient restClient = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(httpClient))
			.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler(__ -> true, (req, res) -> {
			})
			.build();
		String loginPage = restClient.get().uri("/login").retrieve().body(String.class);
		var matcher = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(loginPage);
		matcher.find();
		String csrf = URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
		restClient.post()
			.uri("/login")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("username=test-user&password=test-pass&_csrf=" + csrf)
			.retrieve()
			.toBodilessEntity();
		var response = restClient.get().uri("/admin").retrieve().toEntity(String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getBody()).contains("Error / 403").contains("</html>");
	}

}
