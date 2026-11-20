package am.ik.kagami.buildconfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import am.ik.kagami.buildconfig.ConfigExamples.AuthMethod;
import am.ik.kagami.buildconfig.ConfigExamples.BuildTool;
import am.ik.kagami.buildconfig.ConfigExamples.ConfigExample;
import am.ik.kagami.buildconfig.ConfigExamples.Params;

/**
 * Builds the configuration example panes and the matching tool tabs for a set of
 * repositories. Only the tools of the formats the selection contains are generated, so a
 * token for an npm or PyPI repository does not show Maven specific instructions.
 */
public final class ConfigExampleCatalog {

	private static final List<BuildTool> MAVEN_TOOLS = List.of(BuildTool.MAVEN, BuildTool.GRADLE_GROOVY,
			BuildTool.GRADLE_KOTLIN);

	private static final List<BuildTool> NPM_TOOLS = List.of(BuildTool.NPM);

	private static final List<BuildTool> PYPI_TOOLS = List.of(BuildTool.PIP, BuildTool.PIP_KEYRING, BuildTool.UV_ENV,
			BuildTool.UV_PYPROJECT);

	/**
	 * One repository the configuration examples are generated for.
	 *
	 * @param repositoryId the repository identifier
	 * @param format the repository format
	 * @param isPrivate whether the repository requires authentication
	 */
	public record Selection(String repositoryId, String format, boolean isPrivate) {
	}

	/**
	 * One configuration pane. The field names match the template model.
	 *
	 * @param paneId the pane identifier, unique within the catalog
	 * @param title the pane title
	 * @param filename the file the configuration belongs in
	 * @param content the generated configuration
	 * @param active whether the pane is shown initially
	 */
	public record Pane(String paneId, String title, String filename, String content, boolean active) {
	}

	/**
	 * One tool tab. The pattern selects the panes the tab controls.
	 *
	 * @param pattern the pane id pattern
	 * @param label the tab label
	 * @param active whether the tab is selected initially
	 */
	public record ToolTab(String pattern, String label, boolean active) {
	}

	/**
	 * The generated panes and tabs.
	 *
	 * @param panes the configuration panes
	 * @param toolTabs the tool tabs
	 * @param authTabs whether the username/password and bearer token tabs apply
	 */
	public record Catalog(List<Pane> panes, List<ToolTab> toolTabs, boolean authTabs) {
	}

	private ConfigExampleCatalog() {
	}

	/**
	 * Build the panes for the selected repositories.
	 * @param selections the selected repositories
	 * @param token the JWT token (or a placeholder)
	 * @param baseUrl the base URL of this Kagami instance
	 * @return the panes and tool tabs
	 */
	public static Catalog build(List<Selection> selections, String token, String baseUrl) {
		boolean authTabs = hasMaven(selections) && selections.stream().allMatch(Selection::isPrivate)
				&& onlyMaven(selections);
		List<Pane> panes = new ArrayList<>();
		List<ToolTab> tabs = new ArrayList<>();
		for (String format : List.of("maven", "npm", "pypi")) {
			List<Selection> group = selections.stream().filter(selection -> format.equals(selection.format())).toList();
			if (group.isEmpty()) {
				continue;
			}
			switch (format) {
				case "maven" -> addMaven(panes, tabs, group, token, baseUrl, authTabs);
				case "npm" -> addFormat(panes, tabs, NPM_TOOLS, group, token, baseUrl);
				default -> addFormat(panes, tabs, PYPI_TOOLS, group, token, baseUrl);
			}
		}
		if (!panes.isEmpty()) {
			Pane first = panes.get(0);
			panes.set(0, new Pane(first.paneId(), first.title(), first.filename(), first.content(), true));
			ToolTab firstTab = tabs.get(0);
			tabs.set(0, new ToolTab(firstTab.pattern(), firstTab.label(), true));
		}
		return new Catalog(List.copyOf(panes), List.copyOf(tabs), authTabs);
	}

	private static boolean hasMaven(List<Selection> selections) {
		return selections.stream().anyMatch(selection -> "maven".equals(selection.format()));
	}

	private static boolean onlyMaven(List<Selection> selections) {
		return selections.stream().allMatch(selection -> "maven".equals(selection.format()));
	}

	private static void addMaven(List<Pane> panes, List<ToolTab> tabs, List<Selection> group, String token,
			String baseUrl, boolean authTabs) {
		List<String> repositoryIds = repositoryIds(group);
		boolean isPrivate = group.stream().anyMatch(Selection::isPrivate);
		tabs.add(new ToolTab(BuildTool.MAVEN.paneId() + "-*", BuildTool.MAVEN.label(), false));
		tabs.add(new ToolTab(BuildTool.GRADLE_GROOVY.paneId() + "-*", BuildTool.GRADLE_GROOVY.label(), false));
		tabs.add(new ToolTab(BuildTool.GRADLE_KOTLIN.paneId() + "-*", BuildTool.GRADLE_KOTLIN.label(), false));
		List<AuthMethod> authMethods = authTabs ? List.of(AuthMethod.BASIC, AuthMethod.BEARER)
				: List.of(AuthMethod.BASIC);
		for (BuildTool tool : MAVEN_TOOLS) {
			for (AuthMethod authMethod : authMethods) {
				ConfigExample example = ConfigExamples.generate(tool,
						Params.builder()
							.repositoryIds(repositoryIds)
							.token(token)
							.baseUrl(baseUrl)
							.isPrivate(isPrivate)
							.authMethod(authMethod)
							.build());
				panes.add(new Pane(tool.paneId() + "-" + authMethod.name().toLowerCase(Locale.ROOT), example.title(),
						example.filename(), example.content(), false));
			}
		}
	}

	private static void addFormat(List<Pane> panes, List<ToolTab> tabs, List<BuildTool> tools, List<Selection> group,
			String token, String baseUrl) {
		List<String> repositoryIds = repositoryIds(group);
		boolean isPrivate = group.stream().anyMatch(Selection::isPrivate);
		List<String> patterns = new ArrayList<>();
		for (BuildTool tool : tools) {
			// The keyring option only makes sense when there is a credential to store
			if (tool == BuildTool.PIP_KEYRING && !isPrivate) {
				continue;
			}
			String pattern = tabPattern(tool);
			if (!patterns.contains(pattern)) {
				patterns.add(pattern);
				tabs.add(new ToolTab(pattern, tabLabel(tool), false));
			}
			ConfigExample example = ConfigExamples.generate(tool,
					Params.builder()
						.repositoryIds(repositoryIds)
						.token(token)
						.baseUrl(baseUrl)
						.isPrivate(isPrivate)
						.authMethod(AuthMethod.BASIC)
						.build());
			panes.add(new Pane(tool.paneId(), example.title(), example.filename(), example.content(), false));
		}
	}

	private static String tabPattern(BuildTool tool) {
		return switch (tool) {
			case PIP, PIP_KEYRING -> "pip*";
			case UV_ENV, UV_PYPROJECT -> "uv*";
			default -> tool.paneId();
		};
	}

	private static String tabLabel(BuildTool tool) {
		return switch (tool) {
			case PIP, PIP_KEYRING -> "pip";
			case UV_ENV, UV_PYPROJECT -> "uv";
			default -> tool.label();
		};
	}

	private static List<String> repositoryIds(List<Selection> group) {
		return group.stream().map(Selection::repositoryId).toList();
	}

	/**
	 * The label of a format badge.
	 * @param format the configured format value
	 * @return the display label
	 */
	public static String formatLabel(String format) {
		return switch (format) {
			case "npm" -> "npm";
			case "pypi" -> "PyPI";
			default -> "Maven";
		};
	}

	/**
	 * A selection with its format parsed from the configured value.
	 * @param repositoryId the repository identifier
	 * @param format the configured format value, e.g. {@code npm}
	 * @param isPrivate whether the repository requires authentication
	 * @return the selection
	 * @throws IllegalArgumentException if the format value is unknown
	 */
	public static Selection selection(String repositoryId, String format, boolean isPrivate) {
		return new Selection(Objects.requireNonNull(repositoryId, "repositoryId is required"),
				Objects.requireNonNull(format, "format is required"), isPrivate);
	}

}
