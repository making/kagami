package am.ik.kagami.repository.web;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import am.ik.kagami.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the browser UI tests against S3-compatible object storage
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserControllerTest extends RepositoryControllerTestBase {

	@Test
	void browsePage_directoryEntriesCarryNoTimestamp() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");

		// A directory is a common key prefix, which object storage gives no timestamp:
		// its row shows no relative time value
		String body = bodyOf("/fragments/repositories/test-repo/entries", "path", "org");
		assertThat(body).contains("springframework");
		assertThat(body).doesNotContain("days ago");
	}

}
