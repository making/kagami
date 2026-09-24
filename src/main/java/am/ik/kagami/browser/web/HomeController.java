package am.ik.kagami.browser.web;

import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.browser.BrowserService;
import am.ik.kagami.browser.BrowserService.RepositoryInfo;
import am.ik.kagami.browser.BrowserService.RepositorySummary;
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

	private final KagamiProperties properties;

	private final InstantSource instantSource;

	public HomeController(BrowserService browserService, KagamiProperties properties, InstantSource instantSource) {
		this.browserService = browserService;
		this.properties = properties;
		this.instantSource = instantSource;
	}

	/**
	 * The home page. It only lists the configured repositories; the statistics visit
	 * every stored file and are loaded afterwards by {@link #stats}.
	 */
	@GetMapping("/")
	public String home(Authentication authentication, Model model) {
		model.addAttribute("defaultJwtKey", this.properties.jwt().defaultKeys());
		List<RepositorySummary> repositories = this.browserService.getRepositories();
		model.addAttribute("title", "Maven Mirror Registry");
		model.addAttribute("userName", authentication.getName());
		model.addAttribute("repoCount", repositories.size());
		model.addAttribute("statsPending", true);
		model.addAttribute("repositories", repositories.stream()
			.<RepositoryRow>map(
					repo -> RepositoryRow.builder().id(repo.id()).url(repo.url()).isPrivate(repo.isPrivate()).build())
			.toList());
		return "pages/home";
	}

	/**
	 * Fragment: the repository table with statistics, plus the hero statistics as a
	 * partial.
	 */
	@GetMapping("/fragments/repositories/stats")
	public String stats(Model model) {
		List<RepositoryInfo> repositories = this.browserService.getRepositoryStats();
		long totalArtifacts = repositories.stream().mapToLong(RepositoryInfo::artifactCount).sum();
		long totalSize = repositories.stream().mapToLong(RepositoryInfo::totalSize).sum();
		model.addAttribute("repoCount", repositories.size());
		model.addAttribute("statsPending", false);
		model.addAttribute("totalArtifacts", String.format(Locale.ENGLISH, "%,d", totalArtifacts));
		model.addAttribute("totalSize", Formats.fileSize(totalSize));
		model.addAttribute("repositories",
				repositories.stream()
					.<RepositoryRow>map(repo -> RepositoryRow.builder()
						.id(repo.id())
						.url(repo.url())
						.artifactCount(String.format(Locale.ENGLISH, "%,d", repo.artifactCount()))
						.totalSize(Formats.fileSize(repo.totalSize()))
						.updated(repo.lastUpdated() != null
								? Formats.relativeTime(repo.lastUpdated(), this.instantSource) : null)
						.hasUpdated(repo.lastUpdated() != null)
						.isPrivate(repo.isPrivate())
						.build())
					.toList());
		return "fragments/repository-stats";
	}

	/**
	 * One row of the repository table on the home page. The statistics are absent while
	 * they are still being loaded.
	 */
	record RepositoryRow(String id, String url, @Nullable String artifactCount, @Nullable String totalSize,
			@Nullable String updated, boolean hasUpdated, boolean isPrivate) {

		public boolean pending() {
			return this.artifactCount == null;
		}

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

			public Builder artifactCount(@Nullable String artifactCount) {
				this.artifactCount = artifactCount;
				return this;
			}

			public Builder totalSize(@Nullable String totalSize) {
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
						Objects.requireNonNull(this.url, "url is required"), this.artifactCount, this.totalSize,
						this.updated, this.hasUpdated, this.isPrivate);
			}

		}

	}

}
