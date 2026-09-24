package am.ik.kagami.repository.web;

import am.ik.kagami.repository.RepositoryGarbageCollector;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionFailure;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionResult;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageDirectory;
import am.ik.kagami.repository.RepositoryService;
import am.ik.kagami.repository.RepositoryService.RepositorySummary;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
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

	private final RepositoryService repositoryService;

	private final RepositoryGarbageCollector garbageCollector;

	public RepositoryAdminController(RepositoryService repositoryService, RepositoryGarbageCollector garbageCollector) {
		this.repositoryService = repositoryService;
		this.garbageCollector = garbageCollector;
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
	 * Preview eligible metadata-only directories for the selected repository.
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
	 * Collect eligible metadata-only directories for the selected repository.
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

	private void addPageModel(Model model) {
		List<RepositoryOption> repositories = this.repositoryService.getRepositories()
			.stream()
			.<RepositoryOption>map(RepositoryAdminController::repositoryOption)
			.toList();
		model.addAttribute("title", "Cache Administration");
		model.addAttribute("repositories", repositories);
		model.addAttribute("hasRepositories", !repositories.isEmpty());
		model.addAttribute("defaultOlderThanHours", DEFAULT_OLDER_THAN_HOURS);
	}

	private static RepositoryOption repositoryOption(RepositorySummary repository) {
		return new RepositoryOption(repository.id(), repository.url(), repository.isPrivate());
	}

	private static FailureRow failureRow(GarbageCollectionFailure failure) {
		return new FailureRow(failure.path(), failure.message());
	}

	private static Duration olderThan(long olderThanHours) {
		if (olderThanHours < 1) {
			throw new IllegalArgumentException("Older than must be at least one hour.");
		}
		try {
			return Duration.ofHours(olderThanHours);
		}
		catch (ArithmeticException e) {
			throw new IllegalArgumentException("Older than is too large.", e);
		}
	}

	private static boolean isPartial(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"));
	}

	/**
	 * A configured repository shown in the administration form.
	 */
	record RepositoryOption(String id, String url, boolean isPrivate) {
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
