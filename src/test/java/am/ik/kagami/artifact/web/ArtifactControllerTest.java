package am.ik.kagami.artifact.web;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ArtifactController
 */
@SpringBootTest(properties = { "kagami.repositories.test-central.url=https://repo.maven.apache.org/maven2",
		"kagami.repositories.test-npm.url=http://localhost:1", "kagami.repositories.test-npm.format=npm",
		"logging.level.am.ik.kagami=DEBUG", "spring.security.user.name=test-user",
		"spring.security.user.password=test-password" })
@AutoConfigureMockMvc
@WithMockUser(username = "test-user", password = "test-password",
		authorities = { "artifacts:read", "artifacts:delete" })
class ArtifactControllerTest {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void getArtifact_whenNotInCache_shouldFetchFromRemoteAndCache() throws Exception {
		// First request - artifact not in cache
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.pom"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=junit-4.13.2.pom"))
			.andExpect(header().string("Cache-Control", "max-age=31536000, public"));

		// Verify artifact was cached
		Path cachedFile = tempDir.resolve("test-central/junit/junit/4.13.2/junit-4.13.2.pom");
		assertThat(cachedFile).exists();
		assertThat(Files.size(cachedFile)).isGreaterThan(0);

		// Second request - should serve from cache without hitting remote
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.pom"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"));
	}

	@Test
	void getArtifact_whenRepositoryNotConfigured_shouldReturn404() throws Exception {
		this.mockMvc.perform(get("/artifacts/unknown-repo/some/artifact/1.0/artifact-1.0.jar"))
			.andExpect(status().isNotFound());
	}

	@Test
	void deleteArtifact_whenFileExists_shouldDeleteAndReturn204() throws Exception {
		// Create a test file
		Path testFile = tempDir.resolve("test-central/test/artifact/1.0/artifact-1.0.jar");
		Files.createDirectories(testFile.getParent());
		Files.writeString(testFile, "test content");

		// Delete the file
		this.mockMvc.perform(delete("/artifacts/test-central/test/artifact/1.0/artifact-1.0.jar"))
			.andExpect(status().isNoContent());

		// Verify file was deleted
		assertThat(testFile).doesNotExist();
	}

	@Test
	void deleteArtifact_whenDirectoryExists_shouldDeleteRecursivelyAndReturn204() throws Exception {
		// Create a test directory structure
		Path testDir = tempDir.resolve("test-central/test/artifact/1.0");
		Files.createDirectories(testDir);
		Files.writeString(testDir.resolve("artifact-1.0.jar"), "jar content");
		Files.writeString(testDir.resolve("artifact-1.0.pom"), "pom content");

		// Delete the directory
		this.mockMvc.perform(delete("/artifacts/test-central/test/artifact/1.0/")).andExpect(status().isNoContent());

		// Verify directory was deleted
		assertThat(testDir).doesNotExist();
	}

	@Test
	void deleteArtifact_whenNotExists_shouldReturn404() throws Exception {
		this.mockMvc.perform(delete("/artifacts/test-central/non/existent/artifact.jar"))
			.andExpect(status().isNotFound());
	}

	@Test
	void getArtifact_withDifferentContentTypes() throws Exception {
		// JAR file
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.jar"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/java-archive"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=junit-4.13.2.jar"));

		// SHA1 checksum
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/4.13.2/junit-4.13.2.jar.sha1"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("text/plain"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=junit-4.13.2.jar.sha1"));

		// Maven metadata
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/maven-metadata.xml"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=maven-metadata.xml"));
	}

	@Test
	void getPropertiesFile_whenStored_shouldBeDisplayedInline() throws Exception {
		Path properties = tempDir.resolve("test-central/org/example/resolver-status.properties");
		Files.createDirectories(properties.getParent());
		Files.writeString(properties, "resolver status");

		this.mockMvc.perform(get("/artifacts/test-central/org/example/resolver-status.properties"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("text/plain"))
			.andExpect(content().string("resolver status"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=resolver-status.properties"));
	}

	@Test
	void getArtifact_withEncodedSlashInScopedPackage_shouldReachTheNpmHandler() throws Exception {
		// The npm registry addresses a scoped package with an encoded slash. The router
		// must pass the firewall, decode the path and dispatch to the npm handler, which
		// serves a stored tarball without asking the upstream.
		Path tarball = tempDir.resolve("test-npm/@scope/name/-/name-1.0.0.tgz");
		Files.createDirectories(tarball.getParent());
		Files.writeString(tarball, "tarball");

		// A URI instance keeps the single "%2F" encoding, while a URL template would
		// double-encode the percent sign
		this.mockMvc.perform(get(URI.create("/artifacts/test-npm/@scope%2Fname/-/name-1.0.0.tgz")))
			.andExpect(status().isOk())
			.andExpect(content().string("tarball"));
	}

	@Test
	void getArtifact_withInvalidPath_shouldReturn400() throws Exception {
		this.mockMvc.perform(get("/artifacts/test-npm/too/deep/path")).andExpect(status().isBadRequest());
	}

	@Test
	void headRequest_whenArtifactStored_shouldReturnHeadersWithoutBody() throws Exception {
		Path artifact = tempDir.resolve("test-central/org/example/head-1.0.tar.gz");
		Files.createDirectories(artifact.getParent());
		Files.writeString(artifact, "0123456789");

		this.mockMvc.perform(head("/artifacts/test-central/org/example/head-1.0.tar.gz"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
			.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/gzip"))
			.andExpect(content().string(""));
	}

	@Test
	void rangeRequest_whenArtifactStored_shouldReturnTheRequestedBytes() throws Exception {
		Path artifact = tempDir.resolve("test-central/org/example/range-1.0.whl");
		Files.createDirectories(artifact.getParent());
		Files.writeString(artifact, "0123456789");

		this.mockMvc
			.perform(get("/artifacts/test-central/org/example/range-1.0.whl").header(HttpHeaders.RANGE, "bytes=0-3"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/10"))
			.andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "4"))
			.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
			.andExpect(content().string("0123"));
	}

	@Test
	void conditionalRequest_whenArtifactUnchanged_shouldReturn304() throws Exception {
		Path artifact = tempDir.resolve("test-central/org/example/conditional-1.0.zip");
		Files.createDirectories(artifact.getParent());
		Files.writeString(artifact, "0123456789");

		MvcResult first = this.mockMvc.perform(get("/artifacts/test-central/org/example/conditional-1.0.zip"))
			.andExpect(status().isOk())
			.andReturn();
		String etag = java.util.Objects.requireNonNull(first.getResponse().getHeader(HttpHeaders.ETAG));
		assertThat(etag).isNotBlank();

		this.mockMvc
			.perform(get("/artifacts/test-central/org/example/conditional-1.0.zip").header(HttpHeaders.IF_NONE_MATCH,
					etag))
			.andExpect(status().isNotModified());

		String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusHours(1));
		this.mockMvc
			.perform(get("/artifacts/test-central/org/example/conditional-1.0.zip")
				.header(HttpHeaders.IF_MODIFIED_SINCE, future))
			.andExpect(status().isNotModified());
	}

	@Test
	void getMutableIndex_shouldBeCachedOutsideStorageAndStayOutOfTheBrowseListing() throws Exception {
		this.mockMvc.perform(get("/artifacts/test-central/junit/junit/maven-metadata.xml"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/xml"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, public"));

		// The index is held in the metadata cache, not in the artifact storage
		assertThat(tempDir.resolve("test-central/junit/junit/maven-metadata.xml")).doesNotExist();
		this.mockMvc.perform(get("/browse/test-central/junit/junit"))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("maven-metadata.xml"))));
	}

	@Test
	void getRepositoryMetadata_whenStored_shouldBeDisplayedInline() throws Exception {
		Path metadata = tempDir.resolve("test-central/org/example/lib-1.0/_remote.repositories");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, "lib-1.0.jar>central=");

		this.mockMvc.perform(get("/artifacts/test-central/org/example/lib-1.0/_remote.repositories"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("text/plain"))
			.andExpect(content().string("lib-1.0.jar>central="))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=_remote.repositories"));
	}

}