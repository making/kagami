package am.ik.kagami.repository.web;

import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.buildconfig.ConfigExampleCatalog;
import am.ik.kagami.repository.RepositoryService;
import am.ik.kagami.repository.RepositoryService.RepositoryInfo;
import am.ik.kagami.repository.RepositoryService.RepositorySummary;
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

	private final RepositoryService repositoryService;

	private final KagamiProperties properties;

	private final InstantSource instantSource;

	public HomeController(RepositoryService repositoryService, KagamiProperties properties,
			InstantSource instantSource) {
		this.repositoryService = repositoryService;
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
		List<RepositorySummary> repositories = this.repositoryService.getRepositories();
		model.addAttribute("title", "Maven Mirror Registry");
		model.addAttribute("userName", authentication.getName());
		model.addAttribute("repoCount", repositories.size());
		model.addAttribute("statsPending", true);
		model.addAttribute("formats", FormatOption.all());
		model.addAttribute("repositories",
				repositories.stream()
					.<RepositoryRow>map(repo -> RepositoryRow.builder()
						.id(repo.id())
						.url(repo.url())
						.isPrivate(repo.isPrivate())
						.format(repo.format())
						.formatLabel(ConfigExampleCatalog.formatLabel(repo.format()))
						.build())
					.toList());
		return "pages/home";
	}

	/**
	 * Fragment: the repository table with statistics, plus the hero statistics as a
	 * partial.
	 */
	@GetMapping("/fragments/repositories/stats")
	public String stats(Model model) {
		List<RepositoryInfo> repositories = this.repositoryService.getRepositoryStats();
		long totalArtifacts = repositories.stream().mapToLong(RepositoryInfo::artifactCount).sum();
		long totalSize = repositories.stream().mapToLong(RepositoryInfo::totalSize).sum();
		model.addAttribute("repoCount", repositories.size());
		model.addAttribute("statsPending", false);
		model.addAttribute("formats", FormatOption.all());
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
						.format(repo.format())
						.formatLabel(ConfigExampleCatalog.formatLabel(repo.format()))
						.build())
					.toList());
		return "fragments/repository-stats";
	}

	/**
	 * One row of the repository table on the home page. The statistics are absent while
	 * they are still being loaded.
	 */
	record RepositoryRow(String id, String url, @Nullable String artifactCount, @Nullable String totalSize,
			@Nullable String updated, boolean hasUpdated, boolean isPrivate, String format, String formatLabel) {

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

			@Nullable private String format;

			@Nullable private String formatLabel;

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

			public Builder format(String format) {
				this.format = format;
				return this;
			}

			public Builder formatLabel(String formatLabel) {
				this.formatLabel = formatLabel;
				return this;
			}

			public RepositoryRow build() {
				return new RepositoryRow(Objects.requireNonNull(this.id, "id is required"),
						Objects.requireNonNull(this.url, "url is required"), this.artifactCount, this.totalSize,
						this.updated, this.hasUpdated, this.isPrivate,
						Objects.requireNonNull(this.format, "format is required"),
						Objects.requireNonNull(this.formatLabel, "formatLabel is required"));
			}

		}

	}

	/**
	 * One selectable repository format in the repository filter.
	 *
	 * @param value the format identifier used in {@code data-format}
	 * @param label the display label
	 */
	record FormatOption(String value, String label) {

		static List<FormatOption> all() {
			return List.of(new FormatOption("maven", ConfigExampleCatalog.formatLabel("maven")),
					new FormatOption("npm", ConfigExampleCatalog.formatLabel("npm")),
					new FormatOption("pypi", ConfigExampleCatalog.formatLabel("pypi")));
		}

	}

}
