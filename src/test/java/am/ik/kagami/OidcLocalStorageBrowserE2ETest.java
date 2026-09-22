package am.ik.kagami;

import am.ik.kagami.mockoidc.MockOidcServer;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Runs the browser E2E scenario against the OIDC authentication with a mock OIDC provider
 * (JDK HttpServer) and the local file system storage.
 */
class OidcLocalStorageBrowserE2ETest extends BrowserE2ETestBase {

	static final MockOidcServer MOCK_OIDC_SERVER = new MockOidcServer(TestSocketUtils.findAvailableTcpPort());

	static {
		MOCK_OIDC_SERVER.run();
	}

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		registry.add("kagami.authentication.type", () -> "oidc");
		// Allow every subject so that the mock user is always granted ROLE_USER
		registry.add("kagami.authentication.allowed-name-patterns", () -> ".*");
		registry.add("spring.security.oauth2.client.provider.mock.issuer-uri", MOCK_OIDC_SERVER::issuer);
		registry.add("spring.security.oauth2.client.provider.mock.user-name-attribute", () -> "email");
		registry.add("spring.security.oauth2.client.registration.mock.client-id", () -> "kagami");
		registry.add("spring.security.oauth2.client.registration.mock.client-secret", () -> "mock-secret");
		registry.add("spring.security.oauth2.client.registration.mock.client-name", () -> "Mock OIDC");
		registry.add("spring.security.oauth2.client.registration.mock.scope", () -> "openid,email");
	}

	@Override
	boolean formLoginAvailable() {
		// The OIDC login page shows only the provider buttons, no username/password form
		return false;
	}

	@Override
	String loggedInUser() {
		// The user name comes from the email claim of the id token issued by the mock
		return "user@example.com";
	}

	/**
	 * Logs in through the mock OIDC provider and waits for the home page.
	 */
	@Override
	void login() {
		this.page.navigate("http://localhost:" + this.port + "/");
		assertThat(this.page).hasURL(Pattern.compile("/login$"));
		this.page.locator("a", new Page.LocatorOptions().setHasText("Login with Mock OIDC")).click();
		// The mock provider redirects straight back with an authorization code
		assertThat(this.page).hasURL("http://localhost:" + this.port + "/");
	}

	@Test
	void oidcLoginGrantsAccessAndShowsUser() {
		mirror(POM_PATH, POM_CONTENT);
		login();

		// The header shows the logged in user taken from the id token's email claim
		assertThat(this.page.locator("header").getByText("user@example.com")).isVisible();

		// The repository is browsable after the OIDC login
		Locator repositoryRow = this.page.locator("tbody tr", new Page.LocatorOptions().setHasText("mock"));
		assertThat(repositoryRow).isVisible();
		repositoryRow.click();
		assertThat(this.page).hasURL("http://localhost:" + this.port + "/browse/mock");
		assertThat(this.page.getByText("am", new Page.GetByTextOptions().setExact(true))).isVisible();

		// A token can be generated (requires the USER role granted by the name pattern)
		this.page.navigate("http://localhost:" + this.port + "/token");
		this.page.locator("label", new Page.LocatorOptions().setHasText("mock"))
			.locator("input[type=checkbox]")
			.check();
		this.page.locator("label", new Page.LocatorOptions().setHasText("Read Artifacts"))
			.locator("input[type=checkbox]")
			.check();
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Generate Token")).click();
		assertThat(this.page.getByText("Token Generated")).isVisible();
		assertThat(this.page.locator("div.select-all"))
			.containsText(Pattern.compile("^eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+$"));
	}

}
