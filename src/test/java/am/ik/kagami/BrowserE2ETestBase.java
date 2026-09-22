package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Browser-driven end-to-end test of the web UI, from login to token generation. The
 * storage backend is supplied by the subclass so that every backend runs the same
 * scenario.
 * <p>
 * The UI is built into {@code target/classes/META-INF/resources} by the
 * {@code frontend-maven-plugin} in the {@code compile} phase and Chromium is installed by
 * the {@code exec-maven-plugin} in the {@code process-test-classes} phase, so a plain
 * {@code ./mvnw test} is enough. When running from an IDE, run
 * {@code ./mvnw process-test-classes} once beforehand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.security.user.name=test", "spring.security.user.password={noop}pass" })
@Import(MockConfig.class)
public abstract class BrowserE2ETestBase {

	static final String POM_PATH = "am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom";

	static final String POM_CONTENT = "<project></project>";

	static Playwright playwright;

	static Browser browser;

	@LocalServerPort
	int port;

	@Autowired
	MockServer mockServer;

	@Autowired
	RestClient.Builder restClientBuilder;

	BrowserContext context;

	Page page;

	@BeforeAll
	static void launchBrowser() {
		// Only Chromium is installed by the build; do not download Firefox and WebKit
		playwright = Playwright
			.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
		browser = playwright.chromium().launch();
	}

	@AfterAll
	static void closeBrowser() {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
	}

	@BeforeEach
	void openPage() {
		// Clipboard access is needed for the "Copy" buttons on the token and config UIs
		this.context = browser
			.newContext(new Browser.NewContextOptions().setPermissions(List.of("clipboard-read", "clipboard-write")));
		this.page = this.context.newPage();
		this.page.setDefaultTimeout(15_000);
	}

	/**
	 * Serves the given content (and its SHA-1) from the mock upstream and mirrors it
	 * through the artifact endpoint so that the storage backend under test is populated
	 * by the real fetch path.
	 * @return the SHA-1 of the content
	 */
	String mirror(String path, String content) {
		String sha1 = sha1(content);
		this.mockServer.GET("/" + path, req -> Response.ok(content))
			.GET("/" + path + ".sha1", req -> Response.ok(sha1));
		restClient().get().uri("/artifacts/mock/" + path).retrieve().toBodilessEntity();
		restClient().get().uri("/artifacts/mock/" + path + ".sha1").retrieve().toBodilessEntity();
		return sha1;
	}

	private RestClient restClient() {
		return this.restClientBuilder.baseUrl("http://localhost:" + this.port).build();
	}

	/**
	 * Logs in through the login form and waits for the home page.
	 */
	void login() {
		this.page.navigate("http://localhost:" + this.port + "/");
		assertThat(this.page).hasURL(Pattern.compile("/login$"));
		this.page.fill("#username", "test");
		this.page.fill("#password", "pass");
		this.page.locator("form button[type=submit]").click();
		assertThat(this.page).hasURL("http://localhost:" + this.port + "/");
		// Reload so that the /me state cached while unauthenticated is discarded
		this.page.reload();
		assertThat(this.page).hasURL("http://localhost:" + this.port + "/");
	}

	@AfterEach
	void closePage() {
		this.context.close();
	}

	@Test
	void browseRepositoryAndGenerateToken() {
		String baseUrl = "http://localhost:" + this.port;
		String sha1 = mirror(POM_PATH, POM_CONTENT);

		// Unauthenticated access is redirected to the login page
		login();

		// Repository list shows the mock repository with the mirrored artifact
		Locator repositoryRow = this.page.locator("tbody tr", new Page.LocatorOptions().setHasText("mock"));
		assertThat(repositoryRow).isVisible();
		assertThat(repositoryRow).containsText("1");
		repositoryRow.click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock");

		// Drill into a directory and back via the breadcrumb
		this.page.getByText("am", new Page.GetByTextOptions().setExact(true)).click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock/am");
		assertThat(this.page.getByText("ik", new Page.GetByTextOptions().setExact(true))).isVisible();
		this.page.locator("nav button", new Page.LocatorOptions().setHasText("mock")).click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock");
		assertThat(this.page.getByText("am", new Page.GetByTextOptions().setExact(true))).isVisible();

		// Open the file info modal and check size and checksum
		this.page.navigate(baseUrl + "/browse/mock/am/ik/kagami/kagami/0.0.1");
		// Exact match so that the ".pom.sha1" sibling row is not picked up as well
		Locator fileRow = this.page.locator("div.group", new Page.LocatorOptions()
			.setHas(this.page.getByText("kagami-0.0.1.pom", new Page.GetByTextOptions().setExact(true))));
		fileRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Info")).click();
		// Scope the assertions to the modal; the listing behind it shows the same size
		Locator modal = this.page.locator("div.fixed",
				new Page.LocatorOptions().setHasText("File Information / kagami-0.0.1.pom"));
		assertThat(modal).isVisible();
		assertThat(modal.getByText(POM_CONTENT.length() + " B")).isVisible();
		assertThat(modal.getByText(sha1)).isVisible();
		modal.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Close")).click();
		assertThat(modal).not().isVisible();

		// Generate a token for the mock repository
		this.page.navigate(baseUrl + "/token");
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

	@Test
	void loginFailureShowsError() {
		this.page.navigate("http://localhost:" + this.port + "/");
		assertThat(this.page).hasURL(Pattern.compile("/login$"));
		this.page.fill("#username", "test");
		this.page.fill("#password", "wrong");
		this.page.locator("form button[type=submit]").click();
		assertThat(this.page).hasURL(Pattern.compile("/login\\?error$"));
		assertThat(this.page.locator("#error-message")).isVisible();
		assertThat(this.page.locator("#error-message")).containsText("BadCredentialsException");
	}

	@Test
	void logoutEndsSession() {
		login();

		// The header shows the logged in user and the logout link
		assertThat(this.page.locator("header").getByText("test")).isVisible();

		// The logout page asks for confirmation before ending the session
		this.page.locator("header a", new Page.LocatorOptions().setHasText("Logout")).click();
		assertThat(this.page.locator("#logout-form")).isVisible();
		this.page.locator("#logout-form button[type=submit]").click();

		// Back on the login page with a logged out notice, and the session is really gone
		assertThat(this.page).hasURL(Pattern.compile("/login\\?logout$"));
		assertThat(this.page.getByText("You have been successfully logged out.")).isVisible();
		this.page.navigate("http://localhost:" + this.port + "/");
		// Unauthenticated access keeps the SPA shell but the header no longer knows the
		// user
		assertThat(this.page.locator("header").getByText("Authentication required")).isVisible();
	}

	@Test
	void homePageShowsRepositoriesAndConfigDialog() {
		mirror(POM_PATH, POM_CONTENT);
		login();
		String baseUrl = "http://localhost:" + this.port;

		// The public and the private repository are both listed, the private one badged
		Locator publicRow = this.page.locator("tbody tr", new Page.LocatorOptions().setHasText("mock"));
		assertThat(publicRow).hasCount(1);
		Locator privateRow = this.page.locator("tbody tr", new Page.LocatorOptions().setHasText("secret"));
		assertThat(privateRow).hasCount(1);
		assertThat(privateRow).containsText("Private");

		// Stats on the hero section: two repositories configured
		Locator stats = this.page.locator("section .grid > div").first();
		assertThat(stats).containsText("2");
		assertThat(stats).containsText("Repositories");

		// The config dialog shows build tool configuration for the public repository
		publicRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Config")).click();
		Locator dialog = this.page.locator("div.fixed",
				new Page.LocatorOptions().setHasText("Repository Configuration / mock"));
		assertThat(dialog).isVisible();
		assertThat(dialog.locator("pre code")).containsText("$HOME/.m2/settings.xml");
		assertThat(dialog.getByText("Usage Notes")).isVisible();

		// Switching the build tool tab switches the generated configuration
		dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Gradle (Groovy)")).click();
		assertThat(dialog.locator("pre code")).containsText("$HOME/.gradle/init.gradle");
		dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Gradle (Kotlin)")).click();
		assertThat(dialog.locator("pre code")).containsText("$HOME/.gradle/init.gradle.kts");

		// The public repository has no authentication tabs or notice
		assertThat(dialog.getByText("Authentication Required")).not().isVisible();

		// The close button is icon-only, so close via Escape (handled by the dialog)
		this.page.keyboard().press("Escape");
		assertThat(dialog).not().isVisible();

		// The private repository dialog shows the authentication methods and the notice
		privateRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Config")).click();
		Locator privateDialog = this.page.locator("div.fixed",
				new Page.LocatorOptions().setHasText("Repository Configuration / secret"));
		assertThat(privateDialog).isVisible();
		assertThat(privateDialog.getByText("Username / Password")).isVisible();
		assertThat(privateDialog.getByText("Bearer Token")).isVisible();
		assertThat(privateDialog.getByText("Authentication Required")).isVisible();
		// Bearer auth switches the Maven server block to a Bearer header
		privateDialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Bearer Token")).click();
		assertThat(privateDialog.locator("pre code")).containsText("Bearer YOUR_JWT_TOKEN_HERE");
		this.page.keyboard().press("Escape");
		assertThat(privateDialog).not().isVisible();
	}

	@Test
	void browserPageShowsEmptyAndErrorStates() {
		login();
		String baseUrl = "http://localhost:" + this.port;

		// An unknown repository shows the browse error
		this.page.navigate(baseUrl + "/browse/nonexistent");
		assertThat(this.page.getByText(Pattern.compile("Failed to browse directory: .*"))).isVisible();
	}

	@Test
	void browserPageNavigatesDirectoriesAndParent() {
		mirror("nav/deep/pkg/sample.pom", "<project/>");
		login();
		String baseUrl = "http://localhost:" + this.port;

		// Drill into the nested directory
		this.page.navigate(baseUrl + "/browse/mock/nav");
		assertThat(this.page).hasURL(baseUrl + "/browse/mock/nav");
		this.page.getByText("deep", new Page.GetByTextOptions().setExact(true)).click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock/nav/deep");
		this.page.getByText("pkg", new Page.GetByTextOptions().setExact(true)).click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock/nav/deep/pkg");
		assertThat(this.page.getByText("sample.pom", new Page.GetByTextOptions().setExact(true))).isVisible();

		// The parent directory row climbs back up one level
		this.page.getByText("../ Parent directory").click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock/nav/deep");

		// The repository root has no parent row
		this.page.navigate(baseUrl + "/browse/mock");
		assertThat(this.page.getByText("../ Parent directory")).not().isVisible();
	}

	@Test
	void fileDownloadAndDeleteActions() {
		String path = "actions/tmp/1.0/sample-1.0.pom";
		mirror(path, "<project/>");
		login();
		String baseUrl = "http://localhost:" + this.port;
		this.page.navigate(baseUrl + "/browse/mock/actions/tmp/1.0");

		Locator fileRow = this.page.locator("div.group", new Page.LocatorOptions()
			.setHas(this.page.getByText("sample-1.0.pom", new Page.GetByTextOptions().setExact(true))));

		// Get opens the artifact download URL in a new tab; the endpoint serves the
		// artifact as an attachment, so the popup starts a download
		Queue<Download> downloads = new ConcurrentLinkedQueue<>();
		this.context.onDownload(downloads::add);
		this.page.waitForPopup(
				() -> fileRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Get")).click());
		Download download = null;
		long deadline = System.currentTimeMillis() + 15_000;
		while (download == null && System.currentTimeMillis() < deadline) {
			download = downloads.poll();
			if (download == null) {
				this.page.waitForTimeout(100);
			}
		}
		org.assertj.core.api.Assertions.assertThat(download).as("download").isNotNull();
		org.assertj.core.api.Assertions.assertThat(download.url()).isEqualTo(baseUrl + "/artifacts/mock/" + path);
		download.delete();

		// Dismissing the delete confirmation keeps the file
		fileRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Del")).click();
		assertThat(fileRow).isVisible();

		// Accepting the delete confirmation removes the file and refreshes the listing
		this.page.onceDialog(Dialog::accept);
		fileRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Del")).click();

		// The checksum file remains until deleted as well; then the directory is empty
		Locator shaRow = this.page.locator("div.group", new Page.LocatorOptions()
			.setHas(this.page.getByText("sample-1.0.pom.sha1", new Page.GetByTextOptions().setExact(true))));
		assertThat(shaRow).isVisible();
		this.page.onceDialog(Dialog::accept);
		shaRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Del")).click();
		assertThat(this.page.getByText("This directory is empty.")).isVisible();
	}

	@Test
	void tokenPageValidationAndExpirationWarning() {
		login();
		String baseUrl = "http://localhost:" + this.port;
		this.page.navigate(baseUrl + "/token");

		Locator generateButton = this.page.getByRole(AriaRole.BUTTON,
				new Page.GetByRoleOptions().setName("Generate Token"));

		// The generate button stays disabled until a repository and a scope are selected
		assertThat(generateButton).isDisabled();
		this.page.locator("label", new Page.LocatorOptions().setHasText("mock"))
			.locator("input[type=checkbox]")
			.check();
		assertThat(generateButton).isDisabled();
		this.page.locator("label", new Page.LocatorOptions().setHasText("Read Artifacts"))
			.locator("input[type=checkbox]")
			.check();
		assertThat(generateButton).isEnabled();

		// A duration above six months triggers the long-lived token warning
		this.page.locator("input[type=number]").fill("12");
		assertThat(this.page.getByText("Long-lived token warning")).isVisible();
		// ... and a short one does not
		this.page.locator("select").selectOption("hours");
		this.page.locator("input[type=number]").fill("1");
		assertThat(this.page.getByText("Long-lived token warning")).not().isVisible();
	}

	@Test
	void tokenGenerationShowsResultAndResets() {
		mirror(POM_PATH, POM_CONTENT);
		login();
		String baseUrl = "http://localhost:" + this.port;
		this.page.navigate(baseUrl + "/token");

		this.page.locator("label", new Page.LocatorOptions().setHasText("mock"))
			.locator("input[type=checkbox]")
			.check();
		this.page.locator("label", new Page.LocatorOptions().setHasText("Read Artifacts"))
			.locator("input[type=checkbox]")
			.check();
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Generate Token")).click();

		// The generated token is displayed and can be copied
		assertThat(this.page.getByText("Token Generated")).isVisible();
		assertThat(this.page.locator("div.select-all"))
			.containsText(Pattern.compile("^eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+$"));
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Copy")).first().click();
		assertThat(this.page.getByText("Copied!")).isVisible();

		// Build tool configuration tabs are shown for the generated token
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Gradle (Kotlin)")).click();
		assertThat(this.page.locator("pre code")).containsText("$HOME/.gradle/init.gradle.kts");
		assertThat(this.page.locator("pre code").last()).containsText(baseUrl + "/artifacts/mock");

		// Generating another token resets the form
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Generate Another Token")).click();
		assertThat(this.page.getByText("Token Generated")).not().isVisible();
		assertThat(this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Generate Token")))
			.isDisabled();
	}

	static String sha1(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
