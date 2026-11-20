package am.ik.kagami.registry.pypi;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the PyPI simple index mirror against a mock upstream: link
 * rewriting, content negotiation, the download path, its hash verification and the link
 * host allowlist.
 */
@SpringBootTest(properties = { "spring.security.user.name=test-user", "spring.security.user.password=test-password",
		"logging.level.am.ik.kagami=DEBUG" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password", authorities = { "artifacts:read" })
class PypiMirrorHandlerTest {

	private static final MockServer UPSTREAM = startUpstream();

	@TempDir
	static Path tempDir;

	@Autowired
	private MockMvc mockMvc;

	private static MockServer startUpstream() {
		MockServer mockServer = new MockServer(0);
		mockServer.run();
		return mockServer;
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		String url = "http://127.0.0.1:" + UPSTREAM.port();
		registry.add("kagami.storage.path", () -> tempDir.toString());
		registry.add("kagami.repositories.pypi.url", () -> url);
		registry.add("kagami.repositories.pypi.format", () -> "pypi");
		registry.add("kagami.repositories.restricted.url", () -> url);
		registry.add("kagami.repositories.restricted.format", () -> "pypi");
		registry.add("kagami.repositories.restricted.allowed-hosts", () -> "files.pythonhosted.org");
	}

	@AfterAll
	static void stopUpstream() {
		UPSTREAM.close();
	}

	@BeforeEach
	void resetUpstream() {
		UPSTREAM.reset();
	}

	@Test
	void htmlIndexLinksAreRewrittenAndUnknownAttributesPreserved() throws Exception {
		UPSTREAM.GET("/simple/", request -> Response.ok("""
				<!DOCTYPE html>
				<html><head><title>Simple index</title></head><body>
				<a href="http://127.0.0.1:%d/simple/zope-interface/">zope-interface</a><br/>
				<a href="/simple/requests/">requests</a><br/>
				</body></html>
				""".formatted(UPSTREAM.port()), "text/html"));
		UPSTREAM.GET("/simple/zope-interface/",
				request -> Response
					.ok("""
							<!DOCTYPE html>
							<html><head><title>Links for zope-interface</title></head><body>
							<a href="http://127.0.0.1:%d/files/packages/zope.interface-5.0.0-py3-none-any.whl#sha256=%s" data-requires-python="&gt;=3.6">zope.interface-5.0.0-py3-none-any.whl</a><br/>
							<a href="http://127.0.0.1:%d/files/packages/zope.interface-5.0.0-py3-none-any.whl.metadata#sha256=%s" data-dist-info-metadata="sha256=%s">zope.interface-5.0.0-py3-none-any.whl.metadata</a><br/>
							<a href="http://127.0.0.1:%d/files/packages/zope.interface-5.0.0-py3-none-any.whl.provenance" data-provenance="https://example.com/provenance">provenance</a><br/>
							</body></html>
							"""
						.formatted(UPSTREAM.port(), "a".repeat(64), UPSTREAM.port(), "b".repeat(64), "b".repeat(64),
								UPSTREAM.port()),
							"text/html"));

		String root = this.mockMvc.perform(get("/artifacts/pypi/simple/"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(root).isEqualToNormalizingWhitespace("""
				<!DOCTYPE html>
				<html><head><title>Simple index</title></head><body>
				<a href="http://localhost/artifacts/pypi/simple/zope-interface/">zope-interface</a><br/>
				<a href="http://localhost/artifacts/pypi/simple/requests/">requests</a><br/>
				</body></html>
				""");

		String project = this.mockMvc.perform(get("/artifacts/pypi/simple/zope-interface/"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(project).isEqualToNormalizingWhitespace(
				"""
						<!DOCTYPE html>
						<html><head><title>Links for zope-interface</title></head><body>
						<a href="http://localhost/artifacts/pypi/files/zope-interface/zope.interface-5.0.0-py3-none-any.whl#sha256=%s" data-requires-python="&gt;=3.6">zope.interface-5.0.0-py3-none-any.whl</a><br/>
						<a href="http://localhost/artifacts/pypi/files/zope-interface/zope.interface-5.0.0-py3-none-any.whl.metadata#sha256=%s" data-dist-info-metadata="sha256=%s">zope.interface-5.0.0-py3-none-any.whl.metadata</a><br/>
						<a href="http://localhost/artifacts/pypi/files/zope-interface/zope.interface-5.0.0-py3-none-any.whl.provenance" data-provenance="https://example.com/provenance">provenance</a><br/>
						</body></html>
						"""
					.formatted("a".repeat(64), "b".repeat(64), "b".repeat(64)));
	}

	@Test
	void pep691JsonIsServedWithRewrittenUrlsAndHtmlIsKeptWhenTheUpstreamHasNoJson() throws Exception {
		UPSTREAM.GET("/simple/jsonproject/", request -> {
			if (request.header(HttpHeaders.ACCEPT) != null
					&& request.header(HttpHeaders.ACCEPT).contains("application/vnd.pypi.simple.v1+json")) {
				return Response.ok("""
						{"meta":{"api-version":"1.0"},"name":"jsonproject","versions":["1.0.0","2.0.0"],
						"files":[{"filename":"jsonproject-1.0.0-py3-none-any.whl",
						"url":"http://127.0.0.1:%d/files/packages/jsonproject-1.0.0-py3-none-any.whl#sha256=%s",
						"hashes":{"sha256":"%s"},"requires-python":">=3.8"}]}
						""".formatted(UPSTREAM.port(), "c".repeat(64), "c".repeat(64)),
						"application/vnd.pypi.simple.v1+json");
			}
			return Response
				.ok("""
						<!DOCTYPE html><html><body>
						<a href="http://127.0.0.1:%d/files/packages/jsonproject-1.0.0-py3-none-any.whl#sha256=%s">jsonproject-1.0.0-py3-none-any.whl</a><br/>
						</body></html>
						"""
					.formatted(UPSTREAM.port(), "c".repeat(64)), "text/html");
		});

		this.mockMvc
			.perform(get("/artifacts/pypi/simple/jsonproject/")
				.accept(MediaType.parseMediaType("application/vnd.pypi.simple.v1+json")))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/vnd.pypi.simple.v1+json"))
			.andExpect(jsonPath("$.meta.api-version").value("1.0"))
			.andExpect(jsonPath("$.versions[1]").value("2.0.0"))
			.andExpect(jsonPath("$.files[0].requires-python").value(">=3.8"))
			.andExpect(jsonPath("$.files[0].url")
				.value("http://localhost/artifacts/pypi/files/jsonproject/jsonproject-1.0.0-py3-none-any.whl#sha256="
						+ "c".repeat(64)));

		// The upstream ignores the JSON preference: HTML stays HTML, nothing is
		// fabricated
		UPSTREAM.GET("/simple/htmlonly/", request -> Response
			.ok("<!DOCTYPE html><html><body><a href=\"/files/packages/htmlonly-1.0.0.tar.gz#sha256=%s\">htmlonly-1.0.0.tar.gz</a></body></html>"
				.formatted("d".repeat(64)), "text/html"));

		String html = this.mockMvc
			.perform(get("/artifacts/pypi/simple/htmlonly/")
				.accept(MediaType.parseMediaType("application/vnd.pypi.simple.v1+json")))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(html).isEqualToNormalizingWhitespace(
				"<!DOCTYPE html><html><body><a href=\"http://localhost/artifacts/pypi/files/htmlonly/htmlonly-1.0.0.tar.gz#sha256=%s\">htmlonly-1.0.0.tar.gz</a></body></html>"
					.formatted("d".repeat(64)));
	}

	@Test
	void downloadsAFileOnceVerifiesItsSha256AndRejectsAMismatchingFile() throws Exception {
		byte[] wheel = "the wheel payload".getBytes(StandardCharsets.UTF_8);
		byte[] other = "a different payload".getBytes(StandardCharsets.UTF_8);
		AtomicInteger indexHits = new AtomicInteger();
		AtomicInteger fileHits = new AtomicInteger();
		UPSTREAM.GET("/simple/downloadproject/", request -> {
			indexHits.incrementAndGet();
			return Response
				.ok("""
						<!DOCTYPE html><html><body>
						<a href="http://127.0.0.1:%d/files/packages/download-1.0-py3-none-any.whl#sha256=%s">download-1.0-py3-none-any.whl</a><br/>
						<a href="http://127.0.0.1:%d/files/packages/bad-1.0-py3-none-any.whl#sha256=%s">bad-1.0-py3-none-any.whl</a><br/>
						</body></html>
						"""
					.formatted(UPSTREAM.port(), sha256(wheel), UPSTREAM.port(), sha256(other)), "text/html");
		});
		UPSTREAM.GET("/files/packages/download-1.0-py3-none-any.whl", request -> {
			fileHits.incrementAndGet();
			return Response.builder()
				.contentType("application/octet-stream")
				.body(new String(wheel, StandardCharsets.UTF_8))
				.build();
		});
		UPSTREAM.GET("/files/packages/bad-1.0-py3-none-any.whl",
				request -> Response.builder()
					.contentType("application/octet-stream")
					.body(new String(wheel, StandardCharsets.UTF_8))
					.build());

		// The client first reads the index, then downloads the rewritten file URL
		this.mockMvc.perform(get("/artifacts/pypi/simple/downloadproject/")).andExpect(status().isOk());
		this.mockMvc.perform(get("/artifacts/pypi/files/downloadproject/download-1.0-py3-none-any.whl"))
			.andExpect(status().isOk())
			.andExpect(content().bytes(wheel));
		assertThat(tempDir.resolve("pypi/files/downloadproject/download-1.0-py3-none-any.whl")).exists();

		int indexHitsAfterFirstDownload = indexHits.get();
		this.mockMvc.perform(get("/artifacts/pypi/files/downloadproject/download-1.0-py3-none-any.whl"))
			.andExpect(status().isOk())
			.andExpect(content().bytes(wheel));
		assertThat(fileHits).as("the second request is served from storage").hasValue(1);
		assertThat(indexHits).as("the second request does not even read the index")
			.hasValue(indexHitsAfterFirstDownload);

		// The advertised hash does not match the body: nothing is stored
		this.mockMvc.perform(get("/artifacts/pypi/files/downloadproject/bad-1.0-py3-none-any.whl"))
			.andExpect(status().isBadGateway());
		assertThat(tempDir.resolve("pypi/files/downloadproject/bad-1.0-py3-none-any.whl")).doesNotExist();
	}

	@Test
	void normalizedProjectNamesReachTheSameIndexAndInvalidNamesAreRejected() throws Exception {
		AtomicInteger indexHits = new AtomicInteger();
		UPSTREAM.GET("/simple/normproject/", request -> {
			indexHits.incrementAndGet();
			return Response.ok(
					"<!DOCTYPE html><html><body><a href=\"/files/packages/normproject-1.0.0.tar.gz\">normproject-1.0.0.tar.gz</a></body></html>",
					"text/html");
		});

		this.mockMvc.perform(get("/artifacts/pypi/simple/NormProject/")).andExpect(status().isOk());
		this.mockMvc.perform(get("/artifacts/pypi/simple/normproject/")).andExpect(status().isOk());
		assertThat(indexHits).as("both spellings address one upstream index").hasValue(1);

		this.mockMvc.perform(get("/artifacts/pypi/simple/bad%20name/")).andExpect(status().isBadRequest());
	}

	@Test
	void aLinkOutsideTheAllowlistIsNotFetched() throws Exception {
		AtomicInteger fileHits = new AtomicInteger();
		// The link host (localhost) differs from the repository host (127.0.0.1) and is
		// not in the configured allowlist
		UPSTREAM.GET("/simple/restrictedproject/",
				request -> Response
					.ok("""
							<!DOCTYPE html><html><body>
							<a href="http://localhost:%d/files/packages/restricted-1.0-py3-none-any.whl">restricted-1.0-py3-none-any.whl</a>
							</body></html>
							"""
						.formatted(UPSTREAM.port()), "text/html"));
		UPSTREAM.GET("/files/packages/restricted-1.0-py3-none-any.whl", request -> {
			fileHits.incrementAndGet();
			return Response.ok("payload", "application/octet-stream");
		});

		this.mockMvc.perform(get("/artifacts/restricted/simple/restrictedproject/")).andExpect(status().isOk());
		this.mockMvc.perform(get("/artifacts/restricted/files/restrictedproject/restricted-1.0-py3-none-any.whl"))
			.andExpect(status().isBadGateway());
		assertThat(fileHits).as("a disallowed link is never requested").hasValue(0);
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
