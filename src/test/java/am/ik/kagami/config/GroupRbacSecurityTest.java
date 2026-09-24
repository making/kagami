package am.ik.kagami.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization matrix tests for the group-based RBAC rules: the private repository
 * read/delete rules and the token issuance scope cap, driven through the authorities a
 * web principal holds.
 */
@SpringBootTest(properties = { "kagami.repositories.secure.url=https://repo.maven.apache.org/maven2",
		"kagami.repositories.secure.is-private=true", "spring.security.user.name=test-user",
		"spring.security.user.password=test-password" })
@AutoConfigureMockMvc
class GroupRbacSecurityTest {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
	}

	@Autowired
	MockMvc mockMvc;

	@Test
	@WithMockUser(username = "test-user", authorities = {})
	void privateRepoGetWithoutReadAuthorityIsForbidden() throws Exception {
		this.mockMvc.perform(get("/artifacts/secure/a/b/1.0/a-1.0.pom"))
			.andDo(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
				.isEqualTo(403));
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read" })
	void privateRepoGetWithReadAuthorityReachesTheController() throws Exception {
		// Authorized but the artifact does not exist: 404, not 401/403
		this.mockMvc.perform(get("/artifacts/secure/a/b/1.0/a-1.0.pom")).andExpect(status().isNotFound());
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read" })
	void privateRepoDeleteWithoutDeleteAuthorityIsForbidden() throws Exception {
		this.mockMvc.perform(delete("/artifacts/secure/a/b/1.0/a-1.0.pom")).andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read", "artifacts:delete" })
	void privateRepoDeleteWithDeleteAuthorityDeletes() throws Exception {
		Files.createDirectories(tempDir.resolve("secure/a/b/1.0"));
		Files.writeString(tempDir.resolve("secure/a/b/1.0/a-1.0.pom"), "<project/>");
		this.mockMvc.perform(delete("/artifacts/secure/a/b/1.0/a-1.0.pom")).andExpect(status().isNoContent());
	}

	@Test
	@WithMockUser(username = "test-user", authorities = {})
	void tokenPageIsAccessibleWithZeroAuthorities() throws Exception {
		this.mockMvc.perform(get("/token")).andExpect(status().isOk());
	}

	@Test
	@WithMockUser(username = "test-user", authorities = {})
	void tokenPageHidesScopesAboveTheCap() throws Exception {
		String body = this.mockMvc.perform(get("/token"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		org.assertj.core.api.Assertions.assertThat(body)
			.doesNotContain("artifacts:read")
			.doesNotContain("artifacts:delete");
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read" })
	void tokenIssuanceBeyondTheCapIsForbidden() throws Exception {
		this.mockMvc
			.perform(post("/token").param("expires_in", "1")
				.param("repositories", "secure")
				.param("scope", "artifacts:read,artifacts:delete"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read" })
	void tokenIssuanceWithinTheCapIsAllowed() throws Exception {
		this.mockMvc
			.perform(post("/token").param("expires_in", "1")
				.param("repositories", "secure")
				.param("scope", "artifacts:read"))
			.andExpect(status().isOk());
	}

	@Test
	@WithMockUser(username = "test-user", authorities = { "artifacts:read" })
	void webUiIsAccessibleWithReadAuthorityOnly() throws Exception {
		this.mockMvc.perform(get("/browse/secure"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("secure")));
	}

}
