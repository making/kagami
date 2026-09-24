package am.ik.kagami;

import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Runs the cache administration UI against local file system storage.
 */
class AdminLocalStorageBrowserE2ETest extends BrowserE2ETestBase {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("kagami.storage.path", () -> tempDir.toString());
		registry.add("kagami.rbac.mappings.users.test", () -> "administrators");
	}

	@Test
	void adminCanPreviewAndCollectFromTheScreen() throws IOException {
		Path candidate = seedMetadataOnly("browser");

		login();
		Locator adminLink = this.page.locator("header a", new Page.LocatorOptions().setHasText("Admin"));
		assertThat(adminLink).isVisible();
		adminLink.click();
		assertThat(this.page).hasURL("http://localhost:" + this.port + "/admin");
		assertThat(this.page.getByText("Cache Administration")).isVisible();

		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview")).click();
		assertThat(this.page.getByText("browser/missing")).isVisible();

		this.page.onceDialog(Dialog::accept);
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run Garbage Collection")).click();
		assertThat(this.page.getByText("Collection complete")).isVisible();
		org.assertj.core.api.Assertions.assertThat(Files.exists(candidate)).isFalse();
	}

	private static Path seedMetadataOnly(String name) throws IOException {
		Path candidate = tempDir.resolve("mock").resolve(name).resolve("missing");
		Files.createDirectories(candidate);
		FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
		Files.writeString(candidate.resolve("maven-metadata.xml"), "metadata");
		Files.writeString(candidate.resolve("maven-metadata.xml.sha1"), "checksum");
		Files.setLastModifiedTime(candidate.resolve("maven-metadata.xml"), old);
		Files.setLastModifiedTime(candidate.resolve("maven-metadata.xml.sha1"), old);
		return candidate;
	}

}
