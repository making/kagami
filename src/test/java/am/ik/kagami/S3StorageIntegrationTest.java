package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import am.ik.kagami.rustfs.RustfsContainer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mirrors artifacts into an S3-compatible object storage through the real fetch path and
 * browses them through the web API, the same scenario {@link KagamiIntegrationTest}
 * covers on the local file system.
 */
@SpringBootTest(properties = { "kagami.storage.type=s3", "kagami.storage.s3.key-prefix=mirror",
		"spring.security.user.name=test-user", "spring.security.user.password={noop}pass" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", roles = "USER")
@Import(MockConfig.class)
class S3StorageIntegrationTest {

	private static final String BUCKET = RustfsContainer.createBucket();

	private static final String POM_PATH = "am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom";

	private static final String POM_CONTENT = "<project></project>";

	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		RustfsContainer.registerProperties(registry, BUCKET);
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MockServer mockServer;

	final S3Client s3Client = RustfsContainer.s3Client();

	@BeforeEach
	void resetMockServer() {
		this.mockServer.reset();
	}

	@Test
	void artifactIsFetchedOnceAndThenServedFromTheBucket() throws Exception {
		AtomicInteger remoteRequests = new AtomicInteger();
		this.mockServer.GET("/" + POM_PATH, request -> {
			remoteRequests.incrementAndGet();
			return Response.ok(POM_CONTENT);
		}).GET("/" + POM_PATH + ".sha1", request -> Response.ok(sha1(POM_CONTENT)));

		this.mockMvc.perform(get("/artifacts/mock/" + POM_PATH))
			.andExpect(status().isOk())
			.andExpect(content().string(POM_CONTENT));

		// The artifact is stored under the configured key prefix, with the content type
		// of its extension
		assertThat(objectContent("mirror/mock/" + POM_PATH)).isEqualTo(POM_CONTENT);
		assertThat(objectContentType("mirror/mock/" + POM_PATH)).isEqualTo("application/xml");

		// The second request is served from the storage without going to the remote again
		this.mockMvc.perform(get("/artifacts/mock/" + POM_PATH))
			.andExpect(status().isOk())
			.andExpect(content().string(POM_CONTENT));
		assertThat(remoteRequests).hasValue(1);

		this.mockMvc.perform(delete("/artifacts/mock/" + POM_PATH)).andExpect(status().isNoContent());
		assertThat(objectKeys("mirror/mock/" + POM_PATH)).isEmpty();
		this.mockMvc.perform(delete("/artifacts/mock/" + POM_PATH)).andExpect(status().isNotFound());
	}

	@Test
	void mirroredArtifactsAreBrowsable() throws Exception {
		String path = "am/ik/kagami/browsable/0.0.1/browsable-0.0.1.pom";
		String sha1 = sha1(POM_CONTENT);
		String sha256 = sha256(POM_CONTENT);
		this.mockServer.GET("/" + path, request -> Response.ok(POM_CONTENT))
			.GET("/" + path + ".sha1", request -> Response.ok(sha1))
			.GET("/" + path + ".sha256", request -> Response.ok(sha256));
		this.mockMvc.perform(get("/artifacts/mock/" + path)).andExpect(status().isOk());
		// The resolver verifies the artifact with the .sha1 file but never stores it, so
		// both checksum files are mirrored by requesting them
		this.mockMvc.perform(get("/artifacts/mock/" + path + ".sha1")).andExpect(status().isOk());
		this.mockMvc.perform(get("/artifacts/mock/" + path + ".sha256")).andExpect(status().isOk());

		// A directory listing is built from the common prefixes of the bucket and
		// therefore carries no last modification timestamp
		this.mockMvc.perform(get("/repositories/mock/browse").param("path", "am/ik/kagami"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentPath").value("am/ik/kagami"))
			.andExpect(jsonPath("$.parentPath").value("am/ik"))
			.andExpect(jsonPath("$.entries[?(@.name == 'browsable')].type").value("directory"))
			.andExpect(jsonPath("$.entries[?(@.name == 'browsable')].path").value("am/ik/kagami/browsable"))
			.andExpect(jsonPath("$.entries[?(@.name == 'browsable')].lastModified").doesNotExist());

		this.mockMvc.perform(get("/repositories/mock/browse").param("path", "am/ik/kagami/browsable/0.0.1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[0].name").value("browsable-0.0.1.pom"))
			.andExpect(jsonPath("$.entries[0].type").value("file"))
			.andExpect(jsonPath("$.entries[0].size").value(POM_CONTENT.length()))
			.andExpect(jsonPath("$.entries[0].lastModified").isNotEmpty())
			.andExpect(jsonPath("$.entries[1].name").value("browsable-0.0.1.pom.sha1"))
			.andExpect(jsonPath("$.entries[2].name").value("browsable-0.0.1.pom.sha256"));

		this.mockMvc.perform(get("/repositories/mock/info").param("path", path))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("browsable-0.0.1.pom"))
			.andExpect(jsonPath("$.size").value(POM_CONTENT.length()))
			.andExpect(jsonPath("$.contentType").value("application/xml"))
			.andExpect(jsonPath("$.sha1").value(sha1))
			.andExpect(jsonPath("$.sha256").value(sha256));
	}

	private String objectContent(String key) throws Exception {
		return StreamUtils.copyToString(
				this.s3Client.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build()),
				StandardCharsets.UTF_8);
	}

	private String objectContentType(String key) {
		return this.s3Client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build()).contentType();
	}

	private List<String> objectKeys(String prefix) {
		return this.s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
			.contents()
			.stream()
			.map(object -> object.key())
			.toList();
	}

	private static String sha1(String content) {
		return digest("SHA-1", content);
	}

	private static String sha256(String content) {
		return digest("SHA-256", content);
	}

	private static String digest(String algorithm, String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance(algorithm);
			return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
