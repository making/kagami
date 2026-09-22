package am.ik.kagami.browser.web;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import am.ik.kagami.browser.BrowserService;
import am.ik.kagami.browser.BrowserService.RepositoryInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the repository overview page (home).
 */
@Controller
public class HomeController {

	private final BrowserService browserService;

	public HomeController(BrowserService browserService) {
		this.browserService = browserService;
	}

	@GetMapping("/")
	public String home(Authentication authentication, Model model) {
		List<RepositoryInfo> repositories = this.browserService.getRepositories();
		long totalArtifacts = repositories.stream().mapToLong(RepositoryInfo::artifactCount).sum();
		long totalSize = repositories.stream().mapToLong(RepositoryInfo::totalSize).sum();
		model.addAttribute("title", "Maven Mirror Registry");
		model.addAttribute("userName", authentication.getName());
		model.addAttribute("repoCount", repositories.size());
		model.addAttribute("totalArtifacts", String.format(java.util.Locale.ENGLISH, "%,d", totalArtifacts));
		model.addAttribute("totalSize", Formats.fileSize(totalSize));
		model.addAttribute("repositories",
				repositories.stream()
					.<RepositoryRow>map(repo -> RepositoryRow.builder()
						.id(repo.id())
						.url(repo.url())
						.artifactCount(String.format(Locale.ENGLISH, "%,d", repo.artifactCount()))
						.totalSize(Formats.fileSize(repo.totalSize()))
						.updated(repo.lastUpdated() != null ? Formats.relativeTime(repo.lastUpdated()) : null)
						.hasUpdated(repo.lastUpdated() != null)
						.isPrivate(repo.isPrivate())
						.build())
					.toList());
		return "pages/home";
	}

	/**
	 * One row of the repository table on the home page.
	 */
	record RepositoryRow(String id, String url, String artifactCount, String totalSize, @Nullable String updated,
			boolean hasUpdated, boolean isPrivate) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String id;

			@Nullable private String url;

			@Nullable private String artifactCount;

			@Nullable private String totalSize;

			@Nullable private String updated;

			private boolean hasUpdated;

			private boolean isPrivate;

			private Builder() {
			}

			public Builder id(String id) {
				this.id = id;
				return this;
			}

			public Builder url(String url) {
				this.url = url;
				return this;
			}

			public Builder artifactCount(String artifactCount) {
				this.artifactCount = artifactCount;
				return this;
			}

			public Builder totalSize(String totalSize) {
				this.totalSize = totalSize;
				return this;
			}

			public Builder updated(@Nullable String updated) {
				this.updated = updated;
				return this;
			}

			public Builder hasUpdated(boolean hasUpdated) {
				this.hasUpdated = hasUpdated;
				return this;
			}

			public Builder isPrivate(boolean isPrivate) {
				this.isPrivate = isPrivate;
				return this;
			}

			public RepositoryRow build() {
				return new RepositoryRow(Objects.requireNonNull(this.id, "id is required"),
						Objects.requireNonNull(this.url, "url is required"),
						Objects.requireNonNull(this.artifactCount, "artifactCount is required"),
						Objects.requireNonNull(this.totalSize, "totalSize is required"), this.updated, this.hasUpdated,
						this.isPrivate);
			}

		}

	}

}
