package am.ik.kagami.browser.web;

import am.ik.kagami.rbac.RbacBuiltins;
import java.io.IOException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.buildconfig.ConfigExamples;
import am.ik.kagami.buildconfig.ConfigExamples.AuthMethod;
import am.ik.kagami.buildconfig.ConfigExamples.BuildTool;
import am.ik.kagami.buildconfig.ConfigExamples.ConfigExample;
import am.ik.kagami.buildconfig.ConfigExamples.Params;
import am.ik.kagami.browser.BrowserService;
import am.ik.kagami.browser.BrowserService.BrowseResult;
import am.ik.kagami.browser.BrowserService.FileInfo;
import am.ik.kagami.browser.BrowserService.RepositoryEntry;
import am.ik.kagami.browser.BrowserService.RepositoryInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the repository browse page and the HTML fragments that the htmx powered UI swaps
 * in (directory listing, file info modal, repository config dialog).
 */
@Controller
public class BrowseController {

	private static final List<BuildTool> BUILD_TOOLS = List.of(BuildTool.MAVEN, BuildTool.GRADLE_GROOVY,
			BuildTool.GRADLE_KOTLIN);

	private static final List<AuthMethod> AUTH_METHODS = List.of(AuthMethod.BASIC, AuthMethod.BEARER);

	private final BrowserService browserService;

	private final InstantSource instantSource;

	public BrowseController(BrowserService browserService, InstantSource instantSource) {
		this.browserService = browserService;
		this.instantSource = instantSource;
	}

	/**
	 * The repository browse page for the given path.
	 */
	@GetMapping({ "/browse/{repositoryId}/**", "/browse/{repositoryId}" })
	public String browse(@PathVariable String repositoryId, HttpServletRequest request, Authentication authentication,
			Model model) {
		String path = extractPath(repositoryId, request);
		model.addAttribute("title", "Browse - " + repositoryId);
		model.addAttribute("userName", authentication.getName());
		model.addAttribute("repositoryId", repositoryId);
		model.addAttribute("breadcrumbs", breadcrumbs(repositoryId, path));
		addEntryListModel(model, repositoryId, path, authentication);
		return "pages/browse";
	}

	@GetMapping("/browse")
	public String browseRoot(RedirectAttributes redirectAttributes) {
		return "redirect:/";
	}

	/**
	 * Fragment: the directory listing for the given path. Swapped in when an artifact is
	 * deleted elsewhere in the listing.
	 */
	@GetMapping("/fragments/repositories/{repositoryId}/entries")
	public String entries(@PathVariable String repositoryId, @RequestParam(required = false) String path,
			Authentication authentication, Model model) {
		addEntryListModel(model, repositoryId, path, authentication);
		return "fragments/entry-list";
	}

	/**
	 * Fragment: the file information modal for the given file.
	 */
	@GetMapping("/fragments/repositories/{repositoryId}/info")
	public Object fileInfo(@PathVariable String repositoryId, @RequestParam String path, Model model)
			throws IOException {
		FileInfo info;
		try {
			info = this.browserService.getFileInfo(repositoryId, path);
		}
		catch (IllegalArgumentException e) {
			ModelAndView error = new ModelAndView("fragments/file-info-error");
			error.setStatus(HttpStatus.BAD_REQUEST);
			return error;
		}
		model.addAttribute("title", "File Information / " + info.name());
		model.addAttribute("name", info.name());
		model.addAttribute("path", info.path());
		model.addAttribute("contentType", info.contentType());
		model.addAttribute("size", Formats.fileSize(info.size()));
		model.addAttribute("lastModified", Formats.date(info.lastModified()));
		model.addAttribute("downloadPath", artifactPath(repositoryId, info.path()));
		model.addAttribute("sha1", info.sha1());
		model.addAttribute("hasSha1", info.sha1() != null);
		model.addAttribute("sha256", info.sha256());
		model.addAttribute("hasSha256", info.sha256() != null);
		return "fragments/file-info";
	}

	/**
	 * Fragment: the repository configuration dialog with a pane per build tool and
	 * authentication method.
	 */
	@GetMapping("/fragments/repositories/{repositoryId}/config")
	public String config(@PathVariable String repositoryId, UriComponentsBuilder builder, Model model) {
		String baseUrl = builder.path("").build().toString();
		RepositoryInfo repository = this.browserService.getRepositories()
			.stream()
			.filter(repo -> repo.id().equals(repositoryId))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId));
		model.addAttribute("title", "Repository Configuration / " + repositoryId);
		model.addAttribute("repositoryId", repositoryId);
		model.addAttribute("artifactUrl", baseUrl + "/artifacts/" + repositoryId);
		model.addAttribute("originUrl", repository.url());
		model.addAttribute("isPrivate", repository.isPrivate());
		model.addAttribute("hasAuthTabs", repository.isPrivate());
		model.addAttribute("panesId", "config-dialog-panes");
		model.addAttribute("configs",
				configItems(List.of(repositoryId), repository.isPrivate() ? "YOUR_JWT_TOKEN_HERE" : "", baseUrl,
						repository.isPrivate(), repository.isPrivate()));
		return "fragments/config-dialog";
	}

	private static String extractPath(String repositoryId, HttpServletRequest request) {
		String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		if (fullPath == null) {
			return "";
		}
		String remainder = fullPath.substring(("/browse/" + repositoryId).length());
		return remainder.startsWith("/") ? remainder.substring(1) : remainder;
	}

	/** The pane id prefix for a build tool, matching the tab patterns in the template. */
	private static String paneIdPrefix(BuildTool tool) {
		return switch (tool) {
			case MAVEN -> "maven";
			case GRADLE_GROOVY -> "gradlegroovy";
			case GRADLE_KOTLIN -> "gradlekotlin";
		};
	}

	private static String artifactPath(String repositoryId, String path) {
		return "/artifacts/" + repositoryId + "/" + path;
	}

	private void addEntryListModel(Model model, String repositoryId, @Nullable String path,
			Authentication authentication) {
		// The delete action is only rendered for principals holding the delete authority
		model.addAttribute("canDelete",
				authentication.getAuthorities()
					.stream()
					.anyMatch(authority -> RbacBuiltins.DELETE_AUTHORITY.equals(authority.getAuthority())));
		model.addAttribute("repositoryId", repositoryId);
		model.addAttribute("entriesUrl", entriesUrl(repositoryId, path));
		try {
			BrowseResult result = this.browserService.browseRepository(repositoryId, path);
			model.addAttribute("error", null);
			model.addAttribute("hasError", false);
			model.addAttribute("hasParent", result.parentPath() != null);
			model.addAttribute("parentHref", "/browse/" + repositoryId + (result.parentPath() == null ? ""
					: result.parentPath().isEmpty() ? "" : "/" + result.parentPath()));
			List<EntryRow> entries = new ArrayList<>();
			for (RepositoryEntry entry : result.entries()) {
				boolean isDirectory = "directory".equals(entry.type());
				String deletePath = artifactPath(repositoryId, entry.path()) + (isDirectory ? "/" : "");
				entries.add(EntryRow.builder()
					.name(entry.name())
					.href(isDirectory ? "/browse/" + repositoryId + "/" + entry.path() : null)
					.isDirectory(isDirectory)
					.isFile(!isDirectory)
					.typeLabel(typeLabel(entry.name(), isDirectory))
					.size(entry.size() != null ? Formats.fileSize(entry.size()) : null)
					.hasSize(!isDirectory && entry.size() != null)
					.updated(entry.lastModified() != null
							? Formats.relativeTime(entry.lastModified(), this.instantSource) : null)
					.hasUpdated(!isDirectory && entry.lastModified() != null)
					.downloadPath(isDirectory ? null : artifactPath(repositoryId, entry.path()))
					.infoPath(isDirectory ? null
							: UriComponentsBuilder.fromPath("/fragments/repositories/" + repositoryId + "/info")
								.queryParam("path", entry.path())
								.build()
								.toString())
					.deletePath(deletePath)
					.confirmMessage("Are you sure you want to delete " + (isDirectory ? "directory" : "file") + " \""
							+ entry.name() + "\"?" + (isDirectory ? " This will delete all contents recursively." : ""))
					.build());
			}
			model.addAttribute("entries", entries);
			model.addAttribute("hasEntries", !entries.isEmpty());
		}
		catch (IllegalArgumentException | IOException e) {
			model.addAttribute("error", "Failed to browse directory: " + e.getMessage());
			model.addAttribute("hasError", true);
			model.addAttribute("hasParent", false);
			model.addAttribute("parentHref", null);
			model.addAttribute("entries", List.of());
			model.addAttribute("hasEntries", false);
		}
	}

	private static String entriesUrl(String repositoryId, @Nullable String path) {
		UriComponentsBuilder builder = UriComponentsBuilder
			.fromPath("/fragments/repositories/" + repositoryId + "/entries");
		if (path != null && !path.isEmpty()) {
			builder.queryParam("path", path);
		}
		return builder.build().toString();
	}

	/**
	 * The label shown in the narrow type column: the extension for files, "dir" for
	 * directories, kept short so it fits.
	 */
	private static String typeLabel(String fileName, boolean isDirectory) {
		if (isDirectory) {
			return "dir";
		}
		String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
				: "file";
		return extension.length() > 6 ? extension.substring(0, 5) + "…" : extension;
	}

	private static List<Breadcrumb> breadcrumbs(String repositoryId, String path) {
		List<Breadcrumb> items = new ArrayList<>();
		List<String> parts = path == null || path.isEmpty() ? List.of() : List.of(path.split("/"));
		items.add(new Breadcrumb(repositoryId, "/browse/" + repositoryId, parts.isEmpty(), true));
		StringBuilder pathToHere = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				pathToHere.append('/');
			}
			pathToHere.append(parts.get(i));
			items.add(new Breadcrumb(parts.get(i), "/browse/" + repositoryId + "/" + pathToHere, i == parts.size() - 1,
					false));
		}
		return items;
	}

	private static List<ConfigItem> configItems(List<String> repositoryIds, String token, String baseUrl,
			boolean isPrivate, boolean withAuthMethods) {
		List<AuthMethod> authMethods = withAuthMethods ? AUTH_METHODS : List.of(AuthMethod.BASIC);
		List<ConfigItem> items = new ArrayList<>();
		for (BuildTool tool : BUILD_TOOLS) {
			for (AuthMethod authMethod : authMethods) {
				ConfigExample example = ConfigExamples.generate(tool,
						Params.builder()
							.repositoryIds(repositoryIds)
							.token(token)
							.baseUrl(baseUrl)
							.isPrivate(isPrivate)
							.authMethod(authMethod)
							.build());
				items.add(ConfigItem.builder()
					.paneId(paneIdPrefix(tool) + "-" + authMethod.name().toLowerCase())
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
	 * One breadcrumb entry. The current entry is rendered as a badge, the others as
	 * links.
	 */
	public record Breadcrumb(String name, String href, boolean current, boolean first) {
	}

	/**
	 * One row of the directory listing.
	 */
	public record EntryRow(String name, @Nullable String href, boolean isDirectory, boolean isFile, String typeLabel,
			@Nullable String size, boolean hasSize, @Nullable String updated, boolean hasUpdated,
			@Nullable String downloadPath, @Nullable String infoPath, String deletePath, String confirmMessage) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String name;

			@Nullable private String href;

			private boolean isDirectory;

			private boolean isFile;

			@Nullable private String typeLabel;

			@Nullable private String size;

			private boolean hasSize;

			@Nullable private String updated;

			private boolean hasUpdated;

			@Nullable private String downloadPath;

			@Nullable private String infoPath;

			@Nullable private String deletePath;

			@Nullable private String confirmMessage;

			private Builder() {
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder href(@Nullable String href) {
				this.href = href;
				return this;
			}

			public Builder isDirectory(boolean isDirectory) {
				this.isDirectory = isDirectory;
				return this;
			}

			public Builder isFile(boolean isFile) {
				this.isFile = isFile;
				return this;
			}

			public Builder typeLabel(String typeLabel) {
				this.typeLabel = typeLabel;
				return this;
			}

			public Builder size(@Nullable String size) {
				this.size = size;
				return this;
			}

			public Builder hasSize(boolean hasSize) {
				this.hasSize = hasSize;
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

			public Builder downloadPath(@Nullable String downloadPath) {
				this.downloadPath = downloadPath;
				return this;
			}

			public Builder infoPath(@Nullable String infoPath) {
				this.infoPath = infoPath;
				return this;
			}

			public Builder deletePath(@Nullable String deletePath) {
				this.deletePath = deletePath;
				return this;
			}

			public Builder confirmMessage(String confirmMessage) {
				this.confirmMessage = confirmMessage;
				return this;
			}

			public EntryRow build() {
				return new EntryRow(Objects.requireNonNull(this.name, "name is required"), this.href, this.isDirectory,
						this.isFile, Objects.requireNonNull(this.typeLabel, "typeLabel is required"), this.size,
						this.hasSize, this.updated, this.hasUpdated, this.downloadPath, this.infoPath,
						Objects.requireNonNull(this.deletePath, "deletePath is required"),
						Objects.requireNonNull(this.confirmMessage, "confirmMessage is required"));
			}

		}

	}

	/**
	 * One configuration pane in the config dialog / token result, paired with its tab.
	 */
	public record ConfigItem(String paneId, String title, String filename, String content, boolean active) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String paneId;

			@Nullable private String title;

			@Nullable private String filename;

			@Nullable private String content;

			private boolean active;

			private Builder() {
			}

			public Builder paneId(String paneId) {
				this.paneId = paneId;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder filename(String filename) {
				this.filename = filename;
				return this;
			}

			public Builder content(String content) {
				this.content = content;
				return this;
			}

			public Builder active(boolean active) {
				this.active = active;
				return this;
			}

			public ConfigItem build() {
				return new ConfigItem(Objects.requireNonNull(this.paneId, "paneId is required"),
						Objects.requireNonNull(this.title, "title is required"),
						Objects.requireNonNull(this.filename, "filename is required"),
						Objects.requireNonNull(this.content, "content is required"), this.active);
			}

		}

	}

}
