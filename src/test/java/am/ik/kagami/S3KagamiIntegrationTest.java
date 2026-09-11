package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the artifact fetch and delete flow against the S3 storage backend: an artifact is
 * mirrored from the remote mock repository into the bucket, a second request is served
 * from storage without touching the remote, and DELETE removes the object.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.repositories.mock.is-private=true", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass", "spring.http.clients.redirects=dont_follow" })
@Import(MockConfig.class)
class S3KagamiIntegrationTest {

	private static final String POM_PATH = "com/example/s3lib/1.0/s3lib-1.0.pom";

	private static final String POM_CONTENT = "<project></project>";

	private static final String OBJECT_KEY = "integration/mock/" + POM_PATH;

	RestClient restClient;

	S3Client s3Client;

	@Autowired
	MockServer mockServer;

	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		S3TestSupport.registerS3Properties(registry, "integration");
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder, @LocalServerPort int port) {
		this.restClient = restClientBuilder.baseUrl("http://localhost:" + port)
			.defaultStatusHandler(__ -> true, (req, res) -> {
			})
			.build();
		this.s3Client = S3TestSupport.newClient();
		this.mockServer.reset();
	}

	@Test
	void getArtifactStoresInBucketServesFromStorageOnSecondRequestAndDeleteRemovesIt() {
		AtomicInteger remotePomRequests = new AtomicInteger();
		this.mockServer.GET("/" + POM_PATH, req -> {
			remotePomRequests.incrementAndGet();
			return Response.ok(POM_CONTENT);
		}).GET("/" + POM_PATH + ".sha1", req -> Response.ok(sha1(POM_CONTENT)));

		String token = issueToken(List.of("mock"), List.of("artifacts:read", "artifacts:delete"));

		// First request is not in storage, so it is fetched from the remote and stored
		ResponseEntity<Void> first = this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(remotePomRequests).hasValue(1);

		// The object landed in the bucket with the expected content
		ResponseBytes<?> stored = this.s3Client
			.getObjectAsBytes(GetObjectRequest.builder().bucket(S3TestSupport.BUCKET).key(OBJECT_KEY).build());
		assertThat(stored.asUtf8String()).isEqualTo(POM_CONTENT);

		// Second request is served from storage; the remote is not hit again
		ResponseEntity<Void> second = this.restClient.get()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(remotePomRequests).hasValue(1);

		// DELETE removes the object from the bucket
		ResponseEntity<Void> deleted = this.restClient.delete()
			.uri("/artifacts/mock/" + POM_PATH)
			.headers(httpHeaders -> httpHeaders.setBearerAuth(Objects.requireNonNull(token)))
			.retrieve()
			.toBodilessEntity();
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThatThrownBy(() -> this.s3Client
			.headObject(HeadObjectRequest.builder().bucket(S3TestSupport.BUCKET).key(OBJECT_KEY).build()))
			.isInstanceOf(NoSuchKeyException.class);
	}

	String issueToken(List<String> repositories, List<String> scope) {
		ResponseEntity<String> loginFormResponse = this.restClient.get()
			.uri("/login")
			.retrieve()
			.toEntity(String.class);
		assertThat(loginFormResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(loginFormResponse.getBody()).isNotNull();
		String loginForm = loginFormResponse.getBody();
		Pattern pattern = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
		Matcher matcher = pattern.matcher(loginForm);
		if (!matcher.find()) {
			throw new IllegalStateException("CSRF token not found in the login form");
		}
		String csrfToken = matcher.group(1);
		assertThat(loginFormResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNotNull();
		String cookie = loginFormResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
		ResponseEntity<String> loginResponse = this.restClient.post()
			.uri("/login")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("username=test&password=pass&_csrf=" + csrfToken)
			.header(HttpHeaders.COOKIE, cookie)
			.retrieve()
			.toEntity(String.class);
		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNotNull();
		cookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
		ResponseEntity<String> tokenResponse = this.restClient.post()
			.uri("/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("repositories=" + String.join(",", repositories) + "&scope=" + String.join(",", scope))
			.header(HttpHeaders.COOKIE, cookie)
			.retrieve()
			.toEntity(String.class);
		assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tokenResponse.getBody()).isNotNull();
		return tokenResponse.getBody();
	}

	static String sha1(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
