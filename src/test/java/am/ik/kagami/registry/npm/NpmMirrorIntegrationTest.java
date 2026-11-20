package am.ik.kagami.registry.npm;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the npm registry mirror against a mock upstream: packument
 * rewriting, tarball storage, scoped packages, error mapping, the tarball host allowlist
 * and the registry endpoints that are proxied or answered locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "kagami.upstream.tarball-hosts=registry.npmjs.org", "spring.security.user.name=test",
				"spring.security.user.password={noop}pass", "spring.http.clients.redirects=dont_follow" })
@Import(NpmMirrorIntegrationTest.NpmUpstreamConfig.class)
class NpmMirrorIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TempDir
	static Path storagePath;

	@Autowired
	MockServer upstream;

	@LocalServerPort
	int port;

	RestClient client;

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> storagePath.toString());
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder) {
		this.client = restClientBuilder.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler(__ -> true, (request, response) -> {
			})
			.build();
	}

	@Test
	void packumentIsRewrittenAndTheRewrittenTarballIsStored() throws Exception {
		AtomicInteger tarballHits = new AtomicInteger();
		publishPackument("demo-pack", "1.0.0", "demo-pack-1.0.0.tgz", "sha512-content-hash");
		this.upstream.GET("/demo-pack/1.0.0", request -> Response.json("""
				{"name":"demo-pack","version":"1.0.0","dist":{"tarball":"%s","integrity":"sha512-content-hash"}}
				""".formatted(upstreamUrl("/demo-pack/-/demo-pack-1.0.0.tgz")).replace("\n", "")));
		this.upstream.GET("/demo-pack/-/demo-pack-1.0.0.tgz", request -> {
			tarballHits.incrementAndGet();
			return Response.builder().body("tarball-bytes").contentType("application/octet-stream").build();
		});

		ResponseEntity<String> packument = this.client.get()
			.uri("/artifacts/npm/demo-pack")
			.retrieve()
			.toEntity(String.class);

		assertThat(packument.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(packument.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		JsonNode document = JSON.readTree(Objects.requireNonNull(packument.getBody()));
		JsonNode dist = document.get("versions").get("1.0.0").get("dist");
		assertThat(dist.get("tarball").asText())
			.isEqualTo("http://localhost:%d/artifacts/npm/demo-pack/-/demo-pack-1.0.0.tgz".formatted(this.port));
		// Content hashes stay valid: they describe the tarball, not its URL
		assertThat(dist.get("integrity").asText()).isEqualTo("sha512-content-hash");

		// The abridged response the npm client asks for is rewritten the same way
		ResponseEntity<String> corgi = this.client.get()
			.uri("/artifacts/npm/demo-pack")
			.header(HttpHeaders.ACCEPT, "application/vnd.npm.install-v1+json")
			.retrieve()
			.toEntity(String.class);
		assertThat(corgi.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(corgi.getBody())
			.contains("http://localhost:%d/artifacts/npm/demo-pack/-/demo-pack-1.0.0.tgz".formatted(this.port));

		// HEAD returns the metadata without a body
		ResponseEntity<Void> head = this.client.head().uri("/artifacts/npm/demo-pack").retrieve().toBodilessEntity();
		assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(head.getHeaders().getContentLength()).isPositive();

		// A single version manifest is rewritten in the same way
		ResponseEntity<String> version = this.client.get()
			.uri("/artifacts/npm/demo-pack/1.0.0")
			.retrieve()
			.toEntity(String.class);
		assertThat(version.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(version.getBody())
			.contains("http://localhost:%d/artifacts/npm/demo-pack/-/demo-pack-1.0.0.tgz".formatted(this.port));

		ResponseEntity<String> tarball = this.client.get()
			.uri("/artifacts/npm/demo-pack/-/demo-pack-1.0.0.tgz")
			.retrieve()
			.toEntity(String.class);
		assertThat(tarball.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tarball.getBody()).isEqualTo("tarball-bytes");
		assertThat(tarballHits).hasValue(1);

		ResponseEntity<String> second = this.client.get()
			.uri("/artifacts/npm/demo-pack/-/demo-pack-1.0.0.tgz")
			.retrieve()
			.toEntity(String.class);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody()).isEqualTo("tarball-bytes");
		// Served from storage: the upstream is not asked again
		assertThat(tarballHits).hasValue(1);
	}

	@Test
	void scopedPackagesResolveThroughBothForms() {
		AtomicInteger tarballHits = new AtomicInteger();
		publishPackument("@scope/scoped-pack", "2.0.0", "scoped-pack-2.0.0.tgz", "sha512-scoped");
		this.upstream.GET("/@scope/scoped-pack/-/scoped-pack-2.0.0.tgz", request -> {
			tarballHits.incrementAndGet();
			return Response.ok("scoped-tarball", "application/octet-stream");
		});

		String expectedTarball = "http://localhost:%d/artifacts/npm/@scope/scoped-pack/-/scoped-pack-2.0.0.tgz"
			.formatted(this.port);
		for (String path : List.of("/artifacts/npm/@scope%2Fscoped-pack", "/artifacts/npm/@scope/scoped-pack")) {
			ResponseEntity<String> response = this.client.get().uri(absolute(path)).retrieve().toEntity(String.class);
			assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).as(path).contains(expectedTarball);
		}

		ResponseEntity<String> encoded = this.client.get()
			.uri(absolute("/artifacts/npm/@scope%2Fscoped-pack/-/scoped-pack-2.0.0.tgz"))
			.retrieve()
			.toEntity(String.class);
		assertThat(encoded.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(encoded.getBody()).isEqualTo("scoped-tarball");

		ResponseEntity<String> plain = this.client.get()
			.uri(absolute("/artifacts/npm/@scope/scoped-pack/-/scoped-pack-2.0.0.tgz"))
			.retrieve()
			.toEntity(String.class);
		assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(plain.getBody()).isEqualTo("scoped-tarball");
		assertThat(tarballHits).hasValue(1);
	}

	@Test
	void upstreamFailuresMapTo404And502() {
		this.upstream.GET("/broken-pack", request -> Response.builder().status(500).body("boom").build());

		ResponseEntity<String> missing = this.client.get()
			.uri("/artifacts/npm/missing-pack")
			.retrieve()
			.toEntity(String.class);
		assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<String> broken = this.client.get()
			.uri("/artifacts/npm/broken-pack")
			.retrieve()
			.toEntity(String.class);
		assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void tarballHostOutsideTheAllowlistIsNotFetched() {
		AtomicInteger tarballHits = new AtomicInteger();
		String body = packumentBody("evil-pack", "1.0.0", "sha512-evil",
				"http://localhost:%d/evil-pack/-/evil-pack-1.0.0.tgz".formatted(this.upstream.port()));
		this.upstream.GET("/evil-pack", request -> Response.json(body));
		this.upstream.GET("/evil-pack/-/evil-pack-1.0.0.tgz", request -> {
			tarballHits.incrementAndGet();
			return Response.ok("evil-tarball");
		});

		ResponseEntity<String> response = this.client.get()
			.uri("/artifacts/npm/evil-pack/-/evil-pack-1.0.0.tgz")
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		// The host is reachable but not allowlisted, so the fetch never happens
		assertThat(tarballHits).hasValue(0);
	}

	@Test
	void registryEndpointsAreAnsweredOrProxied() {
		this.upstream.GET("/-/v1/search", request -> Response
			.json("{\"objects\":[],\"total\":0,\"query\":\"" + request.queryParam("text") + "\"}"));
		this.upstream.POST("/-/npm/v1/security/audits/quick",
				request -> Response.builder()
					.status(404)
					.body("{\"error\":\"not implemented\"}")
					.contentType("application/json")
					.build());

		ResponseEntity<String> ping = this.client.get().uri("/artifacts/npm/-/ping").retrieve().toEntity(String.class);
		assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ping.getBody()).isEqualTo("{}");

		ResponseEntity<String> search = this.client.get()
			.uri("/artifacts/npm/-/v1/search?text=kagami")
			.retrieve()
			.toEntity(String.class);
		assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
		// The query string reached the upstream
		assertThat(search.getBody()).contains("\"query\":\"kagami\"");

		ResponseEntity<String> audit = this.client.post()
			.uri("/artifacts/npm/-/npm/v1/security/audits/quick")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"name\":\"demo-pack\"}")
			.retrieve()
			.toEntity(String.class);
		assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(audit.getBody()).contains("not implemented");
	}

	@Test
	void publishIsRejectedAndInvalidPathsAreRejected() {
		ResponseEntity<Void> put = this.client.put()
			.uri("/artifacts/npm/publish-pack")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{}")
			.retrieve()
			.toBodilessEntity();
		assertThat(put.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

		ResponseEntity<String> tooDeep = this.client.get()
			.uri("/artifacts/npm/a/b/c")
			.retrieve()
			.toEntity(String.class);
		assertThat(tooDeep.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		ResponseEntity<String> loneScope = this.client.get()
			.uri("/artifacts/npm/@scope")
			.retrieve()
			.toEntity(String.class);
		assertThat(loneScope.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void upstreamBearerTokenIsSentInsteadOfTheClientAuthorization() {
		this.upstream.GET("/auth-pack",
				request -> "Bearer upstream-token".equals(request.header(HttpHeaders.AUTHORIZATION))
						? Response.json(packumentBody("auth-pack", "1.0.0", "sha512-auth",
								upstreamUrl("/auth-pack/-/auth-pack-1.0.0.tgz")))
						: Response.builder().status(401).body("unauthorized").build());

		ResponseEntity<String> authenticated = this.client.get()
			.uri("/artifacts/npm-auth/auth-pack")
			.retrieve()
			.toEntity(String.class);
		assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.OK);

		// The same upstream without the token refuses the request
		ResponseEntity<String> anonymous = this.client.get()
			.uri("/artifacts/npm/auth-pack")
			.retrieve()
			.toEntity(String.class);
		assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	private void publishPackument(String packageName, String version, String fileName, String integrity) {
		String body = packumentBody(packageName, version, integrity, upstreamUrl("/" + packageName + "/-/" + fileName));
		this.upstream.GET("/" + packageName, request -> Response.json(body));
	}

	private static String packumentBody(String packageName, String version, String integrity, String tarballUrl) {
		return """
				{"name":"%s","dist-tags":{"latest":"%s"},"versions":{"%s":{"name":"%s","version":"%s","dist":{"tarball":"%s","shasum":"0123456789abcdef","integrity":"%s"}}}}
				"""
			.formatted(packageName, version, version, packageName, version, tarballUrl, integrity)
			.replace("\n", "");
	}

	private String upstreamUrl(String path) {
		return "http://127.0.0.1:%d%s".formatted(this.upstream.port(), path);
	}

	private URI absolute(String path) {
		return URI.create("http://localhost:%d%s".formatted(this.port, path));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class NpmUpstreamConfig {

		@Bean
		MockServer npmUpstream() {
			MockServer mockServer = new MockServer(0);
			mockServer.run();
			return mockServer;
		}

		@Bean
		DynamicPropertyRegistrar npmRepositories(MockServer npmUpstream) {
			return registry -> {
				String url = "http://127.0.0.1:%d".formatted(npmUpstream.port());
				registry.add("kagami.repositories.npm.url", () -> url);
				registry.add("kagami.repositories.npm.format", () -> "npm");
				registry.add("kagami.repositories.npm-auth.url", () -> url);
				registry.add("kagami.repositories.npm-auth.format", () -> "npm");
				registry.add("kagami.repositories.npm-auth.token", () -> "upstream-token");
			};
		}

	}

}
