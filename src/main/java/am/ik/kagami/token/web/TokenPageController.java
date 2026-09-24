package am.ik.kagami.token.web;

import am.ik.kagami.rbac.RbacBuiltins;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.repository.RepositoryService;
import am.ik.kagami.rbac.RbacService;
import am.ik.kagami.repository.web.BrowseController.ConfigItem;
import am.ik.kagami.buildconfig.ConfigExamples;
import am.ik.kagami.buildconfig.ConfigExamples.AuthMethod;
import am.ik.kagami.buildconfig.ConfigExamples.BuildTool;
import am.ik.kagami.buildconfig.ConfigExamples.Params;
import am.ik.kagami.token.TokenIssuer;
import am.ik.kagami.token.TokenIssuer.TokenRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves the token generation page and the htmx fragments for generating a token from the
 * web UI. The machine-facing endpoint remains {@link TokenController}.
 */
@Controller
public class TokenPageController {

	private final RepositoryService repositoryService;

	private final TokenIssuer tokenIssuer;

	private final KagamiProperties properties;

	private final RbacService rbacService;

	public TokenPageController(RepositoryService repositoryService, TokenIssuer tokenIssuer,
			KagamiProperties properties, RbacService rbacService) {
		this.repositoryService = repositoryService;
		this.tokenIssuer = tokenIssuer;
		this.properties = properties;
		this.rbacService = rbacService;
	}

	@GetMapping("/token")
	public String page(Authentication authentication, Model model) {
		model.addAttribute("title", "Generate Access Token");
		model.addAttribute("userName", authentication.getName());
		model.addAttribute("defaultJwtKey", this.properties.jwt().defaultKeys());
		addRepositoryModel(model);
		addScopeModel(model, authentication);
		model.addAttribute("error", null);
		model.addAttribute("hasError", false);
		return "pages/token";
	}

	/**
	 * Fragment: a fresh token form, used by "Generate Another Token" to reset the flow.
	 */
	@GetMapping("/app/token/form")
	public String form(Authentication authentication, Model model) {
		addRepositoryModel(model);
		addScopeModel(model, authentication);
		model.addAttribute("error", null);
		model.addAttribute("hasError", false);
		return "fragments/token-form";
	}

	/**
	 * The scope checkboxes the principal may actually issue: scopes above the cap are
	 * hidden from the form.
	 */
	private void addScopeModel(Model model, Authentication authentication) {
		Set<String> issuable = this.rbacService.issuableScopes(authentication);
		model.addAttribute("canRead", issuable.contains(RbacBuiltins.READ_AUTHORITY));
		model.addAttribute("canDelete", issuable.contains(RbacBuiltins.DELETE_AUTHORITY));
	}

	/**
	 * Fragment: generate a token from the web UI form. Returns the token result panel on
	 * success, or the form with a validation error with status 422.
	 */
	@PostMapping("/app/token")
	public Object generate(@RequestParam(defaultValue = "") List<String> repositories,
			@RequestParam(defaultValue = "") Set<String> scope,
			@RequestParam(name = "duration", defaultValue = "6") long duration,
			@RequestParam(name = "unit", defaultValue = "months") String unit, Authentication authentication,
			UriComponentsBuilder builder, Model model) {
		if (repositories.isEmpty() || scope.isEmpty()) {
			return formWithError(model, "Please select at least one repository and one scope.");
		}
		if (!this.rbacService.issuableScopes(authentication).containsAll(scope)) {
			return formWithError(model, "You are not allowed to issue tokens with the selected scopes.");
		}
		if (duration < 1) {
			return formWithError(model, "Duration must be at least 1.");
		}
		long hours = switch (unit) {
			case "days" -> duration * 24;
			case "months" -> duration * 24 * 30;
			default -> duration;
		};
		String token = this.tokenIssuer.issue(TokenRequest.builder()
			.issuer(builder.path("").build().toString())
			.expiresIn(hours)
			.repositories(repositories)
			.scope(scope)
			.userName(authentication.getName())
			.build());
		model.addAttribute("token", token);
		model.addAttribute("panesId", "token-result-panes");
		model.addAttribute("hasAuthTabs", true);
		model.addAttribute("configs", configItems(repositories, token, builder.path("").build().toString()));
		return "fragments/token-result";
	}

	private ModelAndView formWithError(Model model, String message) {
		addRepositoryModel(model);
		model.addAttribute("error", message);
		model.addAttribute("hasError", true);
		ModelAndView modelAndView = new ModelAndView("fragments/token-form");
		modelAndView.setStatus(HttpStatus.UNPROCESSABLE_ENTITY);
		return modelAndView;
	}

	private void addRepositoryModel(Model model) {
		model.addAttribute("repositories",
				this.repositoryService.getRepositories()
					.stream()
					.<RepositoryCheckbox>map(repo -> new RepositoryCheckbox(repo.id(), repo.url(), repo.isPrivate()))
					.toList());
	}

	/** The pane id prefix for a build tool, matching the tab patterns in the template. */
	private static String paneIdPrefix(BuildTool tool) {
		return switch (tool) {
			case MAVEN -> "maven";
			case GRADLE_GROOVY -> "gradlegroovy";
			case GRADLE_KOTLIN -> "gradlekotlin";
		};
	}

	private static List<ConfigItem> configItems(List<String> repositoryIds, String token, String baseUrl) {
		List<ConfigItem> items = new java.util.ArrayList<>();
		for (BuildTool tool : List.of(BuildTool.MAVEN, BuildTool.GRADLE_GROOVY, BuildTool.GRADLE_KOTLIN)) {
			for (AuthMethod authMethod : List.of(AuthMethod.BASIC, AuthMethod.BEARER)) {
				ConfigExamples.ConfigExample example = ConfigExamples.generate(tool,
						Params.builder()
							.repositoryIds(repositoryIds)
							.token(token)
							.baseUrl(baseUrl)
							.isPrivate(true)
							.authMethod(authMethod)
							.build());
				items.add(ConfigItem.builder()
					.paneId(paneIdPrefix(tool) + "-" + authMethod.name().toLowerCase(Locale.ROOT))
					.title(example.title())
					.filename(example.filename())
					.content(example.content())
					.active(tool == BuildTool.MAVEN && authMethod == AuthMethod.BASIC)
					.build());
			}
		}
		return items;
	}

	/**
	 * One repository checkbox on the token form.
	 */
	record RepositoryCheckbox(String id, String url, boolean isPrivate) {
	}

}
