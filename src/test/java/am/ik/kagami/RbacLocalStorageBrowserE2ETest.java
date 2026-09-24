package am.ik.kagami;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Runs the browser E2E scenario against the local file system storage with the logged in
 * user mapped to the built-in read-only group: the delete actions and the delete scope
 * are hidden, while reading and read-token generation keep working.
 */
class RbacLocalStorageBrowserE2ETest extends BrowserE2ETestBase {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		// The static user "test" belongs to the built-in viewers group
		registry.add("kagami.rbac.mappings.user.test", () -> "viewers");
	}

	@Override
	boolean canDelete() {
		return false;
	}

	@Test
	void readOnlyUserSeesNoDeleteActionsAndNoDeleteScope() {
		mirror(POM_PATH, POM_CONTENT);
		login();
		String baseUrl = "http://localhost:" + this.port;

		// The browse page shows no Del buttons for a read-only user
		this.page.navigate(baseUrl + "/browse/mock/am/ik/kagami/kagami/0.0.1");
		assertThat(this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Del"))).hasCount(0);

		// The token form hides the delete scope but keeps the read scope
		this.page.navigate(baseUrl + "/token");
		assertThat(this.page.locator("label", new Page.LocatorOptions().setHasText("Delete Artifacts"))).hasCount(0);
		Locator repositoryCheckbox = this.page.locator("label", new Page.LocatorOptions().setHasText("mock"))
			.locator("input[type=checkbox]");
		repositoryCheckbox.check();
		this.page.locator("label", new Page.LocatorOptions().setHasText("Read Artifacts"))
			.locator("input[type=checkbox]")
			.check();
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Generate Token")).click();
		assertThat(this.page.getByText("Token Generated")).isVisible();
	}

}
