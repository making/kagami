package am.ik.kagami.browser.web;

import am.ik.kagami.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the browser API tests against S3-compatible object storage
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kagami.storage.type=s3")
class S3StorageBrowserControllerTest extends BrowserControllerTestBase {

	@Test
	void browseRepository_directoryEntriesCarryNoLastModified() throws Exception {
		seed("org/springframework/test-file.jar", "dummy jar content");

		this.mockMvc.perform(get("/repositories/test-repo/browse").param("path", "org"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[0].name").value("springframework"))
			.andExpect(jsonPath("$.entries[0].type").value("directory"))
			// A directory is a common key prefix, which object storage gives no timestamp
			.andExpect(jsonPath("$.entries[0].lastModified").doesNotExist());
	}

}
