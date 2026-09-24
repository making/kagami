package am.ik.kagami;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.microsoft.playwright.options.AriaRole;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Runs the browser E2E scenario against the OIDC authentication with a group-based RBAC
 * configuration: the IdP groups claim is translated through
 * {@code kagami.rbac.idp-groups.*} and grants the administrator authorities, while the
 * default group grants nothing.
 */
class OidcRbacLocalStorageBrowserE2ETest extends OidcLocalStorageBrowserE2ETest {

	@DynamicPropertySource
	static void configureRbacProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.rbac.default-group", () -> "no-access");
		registry.add("kagami.rbac.groups.no-access", () -> "");
		registry.add("kagami.rbac.idp-groups.my-admins", () -> "administrators");
	}

	@Override
	boolean canDelete() {
		return true;
	}

	@Override
	boolean standardScenarioApplies() {
		return false;
	}

	@Override
	void login() {
		MOCK_OIDC_SERVER.groups(List.of("my-admins"));
		super.login();
	}

	@Test
	void oidcGroupsClaimGrantsAdministratorAuthorities() {
		mirror(POM_PATH, POM_CONTENT);
		login();
		String baseUrl = "http://localhost:" + this.port;

		// The delete action is rendered: the IdP group was expanded into administrators
		this.page.navigate(baseUrl + "/browse/mock/am/ik/kagami/kagami/0.0.1");
		assertThat(this.page
			.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Page.GetByRoleOptions().setName("Del"))
			.first()).isVisible();

		// The token form offers both scopes
		this.page.navigate(baseUrl + "/token");
		assertThat(this.page.getByText("Delete Artifacts")).isVisible();
		assertThat(this.page.getByText("Read Artifacts")).isVisible();
	}

	@Test
	void oidcUserWithoutMappedGroupGetsNoAuthorities() {
		mirror(POM_PATH, POM_CONTENT);
		// Log in without any IdP groups claim: the empty default group applies
		MOCK_OIDC_SERVER.groups(List.of());
		super.login();
		String baseUrl = "http://localhost:" + this.port;

		// The web UI stays reachable
		assertThat(this.page.locator("header").getByText("user@example.com")).isVisible();
		this.page.navigate(baseUrl + "/browse/mock/am/ik/kagami/kagami/0.0.1");
		assertThat(this.page.getByRole(AriaRole.BUTTON,
				new com.microsoft.playwright.Page.GetByRoleOptions().setName("Del")))
			.hasCount(0);

		// The token form offers no scope at all
		this.page.navigate(baseUrl + "/token");
		assertThat(this.page.getByText("Read Artifacts")).not().isVisible();
		assertThat(this.page.getByText("Delete Artifacts")).not().isVisible();
	}

}
