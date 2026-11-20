package am.ik.kagami.repository.web;

import am.ik.kagami.buildconfig.ConfigExampleCatalog;
import am.ik.kagami.repository.RepositoryGarbageCollector;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionFailure;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionResult;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageDirectory;
import am.ik.kagami.repository.RepositoryRetentionService;
import am.ik.kagami.repository.RepositoryRetentionService.RetentionFailure;
import am.ik.kagami.repository.RepositoryRetentionService.RetentionResult;
import am.ik.kagami.repository.RepositoryService;
import am.ik.kagami.repository.RepositoryService.RepositorySummary;
import am.ik.kagami.support.Durations;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * Serves the cache administration page and its htmx fragments.
 */
@Controller
public class RepositoryAdminController {

	private static final long DEFAULT_OLDER_THAN_HOURS = 1;

	/** The largest accepted age, in hours. */
	private static final long MAX_OLDER_THAN_HOURS = 24 * 365 * 10;

	private final RepositoryService repositoryService;

	private final RepositoryGarbageCollector garbageCollector;

	private final RepositoryRetentionService retentionService;

	public RepositoryAdminController(RepositoryService repositoryService, RepositoryGarbageCollector garbageCollector,
			RepositoryRetentionService retentionService) {
		this.repositoryService = repositoryService;
		this.garbageCollector = garbageCollector;
		this.retentionService = retentionService;
	}

	/**
	 * The cache administration page.
	 */
	@GetMapping("/admin")
	public String page(Model model) {
		addPageModel(model);
		return "pages/admin";
	}

	/**
	 * Preview eligible leftover directories for the selected repository.
	 */
	@GetMapping("/app/admin/gc/preview")
	public Object preview(@RequestParam String repositoryId,
			@RequestParam(name = "olderThanHours", defaultValue = "1") long olderThanHours, HttpServletRequest request,
			Model model) {
		if (!this.repositoryService.findRepository(repositoryId).isPresent()) {
			return error(request, model, HttpStatus.NOT_FOUND, "Repository not found.");
		}
		Duration olderThan;
		try {
			olderThan = olderThan(olderThanHours);
		}
		catch (IllegalArgumentException e) {
			return error(request, model, HttpStatus.UNPROCESSABLE_ENTITY,
					Objects.requireNonNullElse(e.getMessage(), "Invalid age."));
		}
		try {
			List<CandidateRow> candidates = this.garbageCollector.findCandidates(repositoryId, olderThan)
				.stream()
				.<CandidateRow>map(
						candidate -> new CandidateRow(candidate.path(), Formats.date(candidate.lastModified())))
				.toList();
			model.addAttribute("preview", true);
			model.addAttribute("repositoryId", repositoryId);
			model.addAttribute("olderThanHours", olderThanHours);
			model.addAttribute("candidates", candidates);
			model.addAttribute("hasCandidates", !candidates.isEmpty());
			return resultView(request, model, HttpStatus.OK);
		}
		catch (IOException e) {
			return error(request, model, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to inspect repository storage.");
		}
	}

	/**
	 * Collect eligible leftover directories for the selected repository.
	 */
	@PostMapping("/app/admin/gc")
	public Object collect(@RequestParam String repositoryId,
			@RequestParam(name = "olderThanHours", defaultValue = "1") long olderThanHours, HttpServletRequest request,
			Model model) {
		if (!this.repositoryService.findRepository(repositoryId).isPresent()) {
			return error(request, model, HttpStatus.NOT_FOUND, "Repository not found.");
		}
		Duration olderThan;
		try {
			olderThan = olderThan(olderThanHours);
		}
		catch (IllegalArgumentException e) {
			return error(request, model, HttpStatus.UNPROCESSABLE_ENTITY,
					Objects.requireNonNullElse(e.getMessage(), "Invalid age."));
		}
		try {
			GarbageCollectionResult result = this.garbageCollector.collect(repositoryId, olderThan);
			List<FailureRow> failures = result.failures()
				.stream()
				.<FailureRow>map(RepositoryAdminController::failureRow)
				.toList();
			model.addAttribute("completed", true);
			model.addAttribute("repositoryId", repositoryId);
			model.addAttribute("olderThanHours", olderThanHours);
			model.addAttribute("collectedPaths", result.collectedPaths());
			model.addAttribute("hasCollected", !result.collectedPaths().isEmpty());
			model.addAttribute("failures", failures);
			model.addAttribute("hasFailures", !failures.isEmpty());
			return resultView(request, model, HttpStatus.OK);
		}
		catch (IOException e) {
			return error(request, model, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to collect repository cache.");
		}
	}

	/**
	 * Apply the configured retention policy of the selected repository now.
	 */
	@PostMapping("/app/admin/retention")
	public Object applyRetention(@RequestParam String repositoryId, HttpServletRequest request, Model model) {
		if (!this.repositoryService.findRepository(repositoryId).isPresent()) {
			return error(request, model, HttpStatus.NOT_FOUND, "Repository not found.");
		}
		try {
			RetentionResult result = this.retentionService.apply(repositoryId);
			List<FailureRow> failures = result.failures()
				.stream()
				.<FailureRow>map(RepositoryAdminController::failureRow)
				.toList();
			model.addAttribute("retentionCompleted", true);
			model.addAttribute("retentionRepositoryId", repositoryId);
			model.addAttribute("retentionEnabled", this.retentionService.isEnabled(repositoryId));
			model.addAttribute("evictedPaths", result.evictedPaths());
			model.addAttribute("hasEvicted", !result.evictedPaths().isEmpty());
			model.addAttribute("remainingSize", Formats.fileSize(result.remainingSize()));
			model.addAttribute("failures", failures);
			model.addAttribute("hasFailures", !failures.isEmpty());
			return retentionView(request, model, HttpStatus.OK);
		}
		catch (IOException e) {
			return error(request, model, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to apply the retention policy.");
		}
	}

	private Object error(HttpServletRequest request, Model model, HttpStatus status, String message) {
		model.addAttribute("hasError", true);
		model.addAttribute("error", message);
		return resultView(request, model, status);
	}

	private Object resultView(HttpServletRequest request, Model model, HttpStatus status) {
		addPageModel(model);
		ModelAndView view = new ModelAndView(isPartial(request) ? "fragments/admin-gc-result" : "pages/admin");
		view.setStatus(status);
		return view;
	}

	private Object retentionView(HttpServletRequest request, Model model, HttpStatus status) {
		addPageModel(model);
		ModelAndView view = new ModelAndView(isPartial(request) ? "fragments/admin-retention-result" : "pages/admin");
		view.setStatus(status);
		return view;
	}

	private void addPageModel(Model model) {
		List<RepositoryOption> repositories = this.repositoryService.getRepositories()
			.stream()
			.<RepositoryOption>map(this::repositoryOption)
			.toList();
		model.addAttribute("title", "Cache Administration");
		model.addAttribute("repositories", repositories);
		model.addAttribute("hasRepositories", !repositories.isEmpty());
		model.addAttribute("defaultOlderThanHours", DEFAULT_OLDER_THAN_HOURS);
	}

	private RepositoryOption repositoryOption(RepositorySummary repository) {
		return new RepositoryOption(repository.id(), repository.url(), repository.isPrivate(),
				ConfigExampleCatalog.formatLabel(repository.format()), formatSetting(repository),
				retentionSetting(repository.id()), this.retentionService.isEnabled(repository.id()));
	}

	/**
	 * The retention policy summarized on the administration page.
	 */
	private String retentionSetting(String repositoryId) {
		return this.retentionService.retention(repositoryId).map(retention -> {
			List<String> limits = new ArrayList<>();
			if (retention.maxSize() != null) {
				limits.add("max size " + Formats.fileSize(retention.maxSize().toBytes()));
			}
			if (retention.maxAge() != null) {
				limits.add("max age " + durationLabel(retention.maxAge()));
			}
			return String.join(", ", limits);
		}).orElse("Keep forever");
	}

	private static String durationLabel(Duration duration) {
		if (duration.toDays() > 0 && duration.toHours() % 24 == 0) {
			return duration.toDays() + "d";
		}
		if (duration.toHours() > 0 && duration.toMinutes() % 60 == 0) {
			return duration.toHours() + "h";
		}
		if (duration.toMinutes() > 0 && duration.getSeconds() % 60 == 0) {
			return duration.toMinutes() + "m";
		}
		return duration.getSeconds() + "s";
	}

	/**
	 * The format specific settings summarized on the administration page. Secret values
	 * (the upstream bearer token) are only reported as configured or not.
	 */
	private static String formatSetting(RepositorySummary repository) {
		return switch (repository.format()) {
			case "npm" ->
				repository.hasUpstreamToken() ? "Upstream bearer token configured" : "No upstream bearer token";
			case "pypi" ->
				repository.allowedHosts().isEmpty() ? "Allowed hosts: repository host, pypi.org, files.pythonhosted.org"
						: "Allowed hosts: " + String.join(", ", repository.allowedHosts());
			default -> "Maven layout";
		};
	}

	private static FailureRow failureRow(GarbageCollectionFailure failure) {
		return new FailureRow(failure.path(), failure.message());
	}

	private static FailureRow failureRow(RetentionFailure failure) {
		return new FailureRow(failure.path(), failure.message());
	}

	private static Duration olderThan(long olderThanHours) {
		return Durations.hours(olderThanHours, MAX_OLDER_THAN_HOURS, "Older than");
	}

	private static boolean isPartial(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"));
	}

	/**
	 * A configured repository shown in the administration forms.
	 *
	 * @param retentionSetting the configured retention limits, or "Keep forever"
	 * @param retentionEnabled whether a retention limit is configured
	 */
	record RepositoryOption(String id, String url, boolean isPrivate, String formatLabel, String formatSetting,
			String retentionSetting, boolean retentionEnabled) {
	}

	/**
	 * A preview candidate rendered in the result fragment.
	 */
	record CandidateRow(String path, String lastModified) {
	}

	/**
	 * A collection failure rendered in the result fragment.
	 */
	record FailureRow(String path, String message) {
	}

}
