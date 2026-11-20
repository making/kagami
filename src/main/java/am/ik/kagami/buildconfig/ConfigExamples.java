package am.ik.kagami.buildconfig;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Shared configuration examples for build tools, rendered into the copyable code blocks
 * of the token page and the repository config dialog.
 */
public final class ConfigExamples {

	/**
	 * How the JWT token is presented to the build tool: 'basic' uses standard
	 * username/password credentials (the username is arbitrary, the password is the JWT
	 * token), 'bearer' uses an Authorization Bearer header.
	 */
	public enum AuthMethod {

		BASIC, BEARER

	}

	/**
	 * The build tools for which configuration examples are generated. Each tool belongs
	 * to the repository format it consumes and names the tab pane it is rendered in.
	 */
	public enum BuildTool {

		MAVEN("maven", "Maven", "maven"), GRADLE_GROOVY("gradlegroovy", "Gradle (Groovy)", "maven"),
		GRADLE_KOTLIN("gradlekotlin", "Gradle (Kotlin)", "maven"), NPM("npm", "npm", "npm"), PIP("pip", "pip", "pypi"),
		PIP_KEYRING("pipkeyring", "pip (keyring)", "pypi"), UV_ENV("uvenv", "uv (environment)", "pypi"),
		UV_PYPROJECT("uvpyproject", "uv (pyproject.toml)", "pypi");

		private final String paneId;

		private final String label;

		private final String format;

		BuildTool(String paneId, String label, String format) {
			this.paneId = paneId;
			this.label = label;
			this.format = format;
		}

		/**
		 * The prefix of the tab pane this tool is rendered in.
		 * @return the pane id prefix used by the template
		 */
		public String paneId() {
			return this.paneId;
		}

		/**
		 * The label of the tool tab.
		 * @return the human readable tool name
		 */
		public String label() {
			return this.label;
		}

		/**
		 * The repository format this tool consumes.
		 * @return the configured format value this tool consumes
		 */
		public String format() {
			return this.format;
		}

		/**
		 * Whether the tool has one pane per authentication method.
		 * @return {@code true} for the Maven based tools
		 */
		public boolean authVariants() {
			return "maven".equals(this.format);
		}

	}

	/**
	 * @param repositoryIds the repositories the configuration grants access to
	 * @param token the JWT token (or a placeholder)
	 * @param baseUrl the base URL of this Kagami instance
	 * @param isPrivate whether the repositories need authentication
	 * @param authMethod how the token is presented
	 */
	public record Params(List<String> repositoryIds, String token, String baseUrl, boolean isPrivate,
			AuthMethod authMethod) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private List<String> repositoryIds = List.of();

			@Nullable private String token;

			@Nullable private String baseUrl;

			private boolean isPrivate = true;

			@Nullable private AuthMethod authMethod;

			private Builder() {
			}

			public Builder repositoryIds(List<String> repositoryIds) {
				this.repositoryIds = repositoryIds;
				return this;
			}

			public Builder token(String token) {
				this.token = token;
				return this;
			}

			public Builder baseUrl(String baseUrl) {
				this.baseUrl = baseUrl;
				return this;
			}

			public Builder isPrivate(boolean isPrivate) {
				this.isPrivate = isPrivate;
				return this;
			}

			public Builder authMethod(AuthMethod authMethod) {
				this.authMethod = authMethod;
				return this;
			}

			public Params build() {
				return new Params(Objects.requireNonNull(this.repositoryIds, "repositoryIds is required"),
						Objects.requireNonNull(this.token, "token is required"),
						Objects.requireNonNull(this.baseUrl, "baseUrl is required"), this.isPrivate,
						Objects.requireNonNull(this.authMethod, "authMethod is required"));
			}

		}

	}

	/**
	 * A generated configuration example: its title, the file it belongs in and its
	 * content.
	 */
	public record ConfigExample(String title, String filename, String content) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String title;

			@Nullable private String filename;

			@Nullable private String content;

			private Builder() {
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

			public ConfigExample build() {
				return new ConfigExample(Objects.requireNonNull(this.title, "title is required"),
						Objects.requireNonNull(this.filename, "filename is required"),
						Objects.requireNonNull(this.content, "content is required"));
			}

		}

	}

	private ConfigExamples() {
	}

	/**
	 * Generate the configuration example for the given build tool.
	 */
	public static ConfigExample generate(BuildTool tool, Params params) {
		return switch (tool) {
			case MAVEN -> mavenConfig(params);
			case GRADLE_GROOVY -> gradleGroovyConfig(params);
			case GRADLE_KOTLIN -> gradleKotlinConfig(params);
			case NPM -> npmConfig(params);
			case PIP -> pipConfig(params);
			case PIP_KEYRING -> pipKeyringConfig(params);
			case UV_ENV -> uvEnvConfig(params);
			case UV_PYPROJECT -> uvPyprojectConfig(params);
		};
	}

	/**
	 * The URL a client uses as the npm registry of a repository.
	 */
	private static String npmRegistryUrl(String baseUrl, String repositoryId) {
		return baseUrl + "/artifacts/" + repositoryId + "/";
	}

	/**
	 * The registry URL without the scheme, the key prefix of an npm authentication line.
	 */
	private static String npmRegistryKey(String baseUrl, String repositoryId) {
		URI uri = URI.create(baseUrl);
		String path = Objects.requireNonNullElse(uri.getRawPath(), "");
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return uri.getRawAuthority() + path + "/artifacts/" + repositoryId + "/";
	}

	/**
	 * The URL a client uses as the PyPI simple index of a repository.
	 */
	private static String pypiIndexUrl(String baseUrl, String repositoryId) {
		return baseUrl + "/artifacts/" + repositoryId + "/simple/";
	}

	/**
	 * Embed Basic credentials into a URL. The username is arbitrary, the password is the
	 * JWT token.
	 */
	private static String embedCredentials(String url, String token) {
		URI uri = URI.create(url);
		return uri.getScheme() + "://kagami:" + token + "@" + uri.getRawAuthority() + uri.getRawPath();
	}

	/**
	 * The host (with port) of a URL, used for the trusted-host and keyring settings.
	 */
	private static String hostOf(String url) {
		return URI.create(url).getRawAuthority();
	}

	private static boolean isHttp(String baseUrl) {
		return baseUrl.startsWith("http://");
	}

	/**
	 * The hosts that need an insecure-transport allowance, one per repository, only for
	 * plain HTTP mirrors.
	 */
	private static List<String> insecureHosts(String baseUrl, List<String> repositoryIds) {
		if (!isHttp(baseUrl)) {
			return List.of();
		}
		return repositoryIds.stream()
			.map(repositoryId -> hostOf(pypiIndexUrl(baseUrl, repositoryId)))
			.distinct()
			.toList();
	}

	private static ConfigExample npmConfig(Params params) {
		List<String> repositoryIds = params.repositoryIds();
		String baseUrl = params.baseUrl();
		String primaryRepo = repositoryIds.get(0);
		StringBuilder content = new StringBuilder("# $HOME/.npmrc\n");
		content.append("registry=").append(npmRegistryUrl(baseUrl, primaryRepo)).append('\n');
		if (params.isPrivate()) {
			for (String repositoryId : repositoryIds) {
				content.append("//")
					.append(npmRegistryKey(baseUrl, repositoryId))
					.append(":_authToken=")
					.append(params.token())
					.append('\n');
			}
		}
		for (String repositoryId : repositoryIds) {
			if (repositoryId.startsWith("@")) {
				String scope = repositoryId.split("/")[0];
				content.append(scope).append(":registry=").append(npmRegistryUrl(baseUrl, repositoryId)).append('\n');
			}
		}
		return new ConfigExample("npm Configuration", "$HOME/.npmrc", content.toString());
	}

	/** How a pip index URL carries credentials. */
	private enum PipCredential {

		NONE, URL, USERNAME

	}

	private static String pipIndexSection(Params params, PipCredential credential) {
		List<String> repositoryIds = params.repositoryIds();
		StringBuilder content = new StringBuilder("[global]\n");
		for (int i = 0; i < repositoryIds.size(); i++) {
			String url = pypiIndexUrl(params.baseUrl(), repositoryIds.get(i));
			if (params.isPrivate()) {
				url = switch (credential) {
					case NONE -> url;
					case URL -> embedCredentials(url, params.token());
					case USERNAME -> url.replaceFirst("://", "://kagami@");
				};
			}
			content.append(i == 0 ? "index-url = " : "extra-index-url = ").append(url).append('\n');
		}
		for (String host : insecureHosts(params.baseUrl(), repositoryIds)) {
			content.append("trusted-host = ").append(host).append('\n');
		}
		return content.toString();
	}

	private static ConfigExample pipConfig(Params params) {
		return new ConfigExample("pip Configuration", "$HOME/.config/pip/pip.conf",
				pipIndexSection(params, PipCredential.URL));
	}

	private static ConfigExample pipKeyringConfig(Params params) {
		StringBuilder content = new StringBuilder(pipIndexSection(params, PipCredential.USERNAME));
		content.append('\n')
			.append("# pip reads the password from the system keyring instead of this file:\n")
			.append("#   pip install keyring\n");
		for (String repositoryId : params.repositoryIds()) {
			content.append("#   keyring set ")
				.append(hostOf(pypiIndexUrl(params.baseUrl(), repositoryId)))
				.append(" kagami\n");
		}
		content.append("# Enter the JWT token when prompted.\n");
		return new ConfigExample("pip Configuration (keyring)", "$HOME/.config/pip/pip.conf", content.toString());
	}

	private static ConfigExample uvEnvConfig(Params params) {
		List<String> repositoryIds = params.repositoryIds();
		StringBuilder content = new StringBuilder("# Set these environment variables before running uv\n");
		if (params.isPrivate()) {
			content.append("# The user name is arbitrary, the password is the JWT token.\n");
		}
		for (int i = 0; i < repositoryIds.size(); i++) {
			String url = pypiIndexUrl(params.baseUrl(), repositoryIds.get(i));
			if (params.isPrivate()) {
				url = embedCredentials(url, params.token());
			}
			content.append("export ")
				.append(i == 0 ? "UV_INDEX_URL" : "UV_EXTRA_INDEX_URL")
				.append("=\"")
				.append(url)
				.append("\"\n");
		}
		for (String host : insecureHosts(params.baseUrl(), repositoryIds)) {
			content.append("export UV_INSECURE_HOST=\"").append(host).append("\"\n");
		}
		return new ConfigExample("uv Configuration (environment)", "environment variables", content.toString());
	}

	private static ConfigExample uvPyprojectConfig(Params params) {
		List<String> repositoryIds = params.repositoryIds();
		StringBuilder content = new StringBuilder();
		if (params.isPrivate()) {
			content.append("# Provide credentials through the environment:\n");
			for (String repositoryId : repositoryIds) {
				String name = uvIndexName(repositoryId);
				content.append("#   export UV_INDEX_")
					.append(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"))
					.append("_USERNAME=kagami\n");
				content.append("#   export UV_INDEX_")
					.append(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"))
					.append("_PASSWORD=<JWT token>\n");
			}
			content.append('\n');
		}
		for (int i = 0; i < repositoryIds.size(); i++) {
			content.append("[[tool.uv.index]]\n")
				.append("name = \"")
				.append(uvIndexName(repositoryIds.get(i)))
				.append("\"\n")
				.append("url = \"")
				.append(pypiIndexUrl(params.baseUrl(), repositoryIds.get(i)))
				.append("\"\n");
			if (i == 0) {
				content.append("default = true\n");
			}
			content.append('\n');
		}
		List<String> hosts = insecureHosts(params.baseUrl(), repositoryIds);
		if (!hosts.isEmpty()) {
			content.append("[tool.uv]\nallow-insecure-host = [")
				.append(hosts.stream().map(host -> "\"" + host + "\"").collect(Collectors.joining(", ")))
				.append("]\n");
		}
		return new ConfigExample("uv Configuration (pyproject.toml)", "pyproject.toml", content.toString());
	}

	private static String uvIndexName(String repositoryId) {
		String sanitized = repositoryId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
		return "kagami-" + (sanitized.isEmpty() ? "index" : sanitized);
	}

	private static String mavenServer(String repo, String token, AuthMethod authMethod) {
		// String concatenation instead of a text block: the fixed four-space base
		// indentation of the generated XML matters and must survive formatting.
		if (authMethod == AuthMethod.BEARER) {
			return "    <server>\n" + "      <id>kagami-" + repo + "</id>\n" + "      <configuration>\n"
					+ "        <httpHeaders>\n" + "          <property>\n" + "            <name>Authorization</name>\n"
					+ "            <value>Bearer " + token + "</value>\n" + "          </property>\n"
					+ "        </httpHeaders>\n" + "      </configuration>\n" + "    </server>";
		}
		return "    <server>\n" + "      <id>kagami-" + repo + "</id>\n" + "      <username>kagami</username>\n"
				+ "      <password>" + token + "</password>\n" + "    </server>";
	}

	private static String mavenServersSection(List<String> repositoryIds, String token, AuthMethod authMethod) {
		String comment = authMethod == AuthMethod.BASIC
				? "    <!-- The username can be any value. The password is the JWT token. -->\n" : "";
		String servers = repositoryIds.stream()
			.map(repo -> mavenServer(repo, token, authMethod))
			.collect(Collectors.joining("\n"));
		return "  <servers>\n" + comment + servers + "\n  </servers>\n";
	}

	private static String gradleGroovyCredentials(String indent, AuthMethod authMethod) {
		if (authMethod == AuthMethod.BEARER) {
			return """

					%sauthentication {
					%s    header(HttpHeaderAuthentication)
					%s}
					%scredentials(HttpHeaderCredentials) {
					%s    name = "Authorization"
					%s    value = repoToken
					%s}""".formatted(indent, indent, indent, indent, indent, indent, indent);
		}
		return """

				%scredentials {
				%s    username = "kagami" // can be any value
				%s    password = repoToken
				%s}""".formatted(indent, indent, indent, indent);
	}

	private static String gradleKotlinCredentials(String indent, AuthMethod authMethod) {
		if (authMethod == AuthMethod.BEARER) {
			return """

					%sauthentication {
					%s    create<HttpHeaderAuthentication>("header")
					%s}
					%scredentials(HttpHeaderCredentials::class) {
					%s    name = "Authorization"
					%s    value = repoToken
					%s}""".formatted(indent, indent, indent, indent, indent, indent, indent);
		}
		return """

				%scredentials {
				%s    username = "kagami" // can be any value
				%s    password = repoToken
				%s}""".formatted(indent, indent, indent, indent);
	}

	private static String gradleTokenValue(String token, AuthMethod authMethod) {
		return authMethod == AuthMethod.BEARER ? "Bearer " + token : token;
	}

	/**
	 * Credentials blocks only appear for private repositories, matching the previous
	 * behavior.
	 */
	private static String credentials(Params params, boolean groovyDsl, String indent, AuthMethod authMethod) {
		if (!params.isPrivate()) {
			return "";
		}
		return groovyDsl ? gradleGroovyCredentials(indent, authMethod) : gradleKotlinCredentials(indent, authMethod);
	}

	private static String gradleInsecure(String indent, boolean isHttpUrl) {
		return isHttpUrl ? "\n" + indent + "allowInsecureProtocol = true" : "";
	}

	private static String gradleKotlinInsecure(String indent, boolean isHttpUrl) {
		return isHttpUrl ? "\n" + indent + "isAllowInsecureProtocol = true" : "";
	}

	private static String capitalize(String value) {
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private static ConfigExample mavenConfig(Params params) {
		List<String> repositoryIds = params.repositoryIds();
		String primaryRepo = repositoryIds.get(0);
		String title = "Maven Configuration";
		String filename = "$HOME/.m2/settings.xml";
		String servers = params.isPrivate() ? mavenServersSection(repositoryIds, params.token(), params.authMethod())
				: "";
		if (repositoryIds.size() == 1) {
			// Built by concatenation to keep the exact indentation of the generated XML
			String repoUrl = params.baseUrl() + "/artifacts/" + primaryRepo;
			String repository = "      <repositories>\n" + "        <repository>\n" + "          <id>kagami-"
					+ primaryRepo + "</id>\n" + "          <name>Kagami Repository - " + primaryRepo + "</name>\n"
					+ "          <url>" + repoUrl + "</url>\n" + "          <snapshots>\n"
					+ "            <enabled>true</enabled>\n" + "          </snapshots>\n" + "        </repository>\n"
					+ "      </repositories>\n" + "      <pluginRepositories>\n" + "        <pluginRepository>\n"
					+ "          <id>kagami-" + primaryRepo + "</id>\n" + "          <name>Kagami Repository - "
					+ primaryRepo + "</name>\n" + "          <url>" + repoUrl + "</url>\n" + "          <snapshots>\n"
					+ "            <enabled>true</enabled>\n" + "          </snapshots>\n"
					+ "        </pluginRepository>\n" + "      </pluginRepositories>";
			String content = """
					<!-- RECOMMENDED: Add to your $HOME/.m2/settings.xml -->
					<settings>
					%s  <profiles>
					    <profile>
					      <id>kagami-%s</id>
					      <activation>
					        <activeByDefault>true</activeByDefault>
					      </activation>
					%s
					    </profile>
					  </profiles>

					  <!-- ALTERNATIVE: Mirror configuration -->
					  <!-- Use mirrors when you want to redirect ALL Maven repository requests through Kagami -->
					  <!-- This is useful for: -->
					  <!-- - Corporate environments where all external access must go through a proxy -->
					  <!-- - Offline environments where only Kagami has access to external repositories -->
					  <!-- - Performance optimization when Kagami has better network access to upstream repos -->
					  <!--
					  <mirrors>
					    <mirror>
					      <id>kagami-%s</id>
					      <mirrorOf>*</mirrorOf>
					      <name>Kagami Mirror - %s</name>
					      <url>%s/artifacts/%s</url>
					    </mirror>
					  </mirrors>
					  -->

					  <!-- SIMPLE: Add directly to your pom.xml (project-specific) -->
					  <!--
					  <repositories>
					    <repository>
					      <id>kagami-%s</id>
					      <name>%s Repository</name>
					      <url>%s/artifacts/%s</url>
					    </repository>
					  </repositories>
					  -->
					</settings>""".formatted(servers, primaryRepo, repository, primaryRepo, primaryRepo,
					params.baseUrl(), primaryRepo, primaryRepo, capitalize(primaryRepo), params.baseUrl(), primaryRepo);
			return new ConfigExample(title, filename, content);
		}
		// Built by concatenation to keep the exact indentation of the generated XML
		String repositories = repositoryIds.stream()
			.map(repo -> "        <repository>\n" + "          <id>kagami-" + repo + "</id>\n"
					+ "          <name>Kagami Repository - " + repo + "</name>\n" + "          <url>" + params.baseUrl()
					+ "/artifacts/" + repo + "</url>\n" + "          <snapshots>\n"
					+ "            <enabled>true</enabled>\n" + "          </snapshots>\n" + "        </repository>")
			.collect(Collectors.joining("\n"));
		String pluginRepositories = repositoryIds.stream()
			.map(repo -> "        <pluginRepository>\n" + "          <id>kagami-" + repo + "</id>\n"
					+ "          <name>Kagami Repository - " + repo + "</name>\n" + "          <url>" + params.baseUrl()
					+ "/artifacts/" + repo + "</url>\n" + "          <snapshots>\n"
					+ "            <enabled>true</enabled>\n" + "          </snapshots>\n"
					+ "        </pluginRepository>")
			.collect(Collectors.joining("\n"));
		String content = """
				<!-- RECOMMENDED: Add to your $HOME/.m2/settings.xml -->
				<settings>
				%s  <profiles>
				    <profile>
				      <id>kagami-multiple</id>
				      <activation>
				        <activeByDefault>true</activeByDefault>
				      </activation>
				      <repositories>
				%s
				      </repositories>
				      <pluginRepositories>
				%s
				      </pluginRepositories>
				    </profile>
				  </profiles>

				  <!-- ALTERNATIVE: Mirror configuration for multiple repositories -->
				  <!-- Note: Mirrors can only redirect to ONE target URL, so this approach works -->
				  <!-- only if all selected repositories are accessible through a single Kagami endpoint -->
				  <!-- Use mirrors when you want to redirect ALL Maven repository requests through Kagami -->
				  <!-- This is useful for: -->
				  <!-- - Corporate environments where all external access must go through a proxy -->
				  <!-- - Offline environments where only Kagami has access to external repositories -->
				  <!-- - Performance optimization when Kagami has better network access to upstream repos -->
				  <!--
				  <mirrors>
				    <mirror>
				      <id>kagami-all</id>
				      <mirrorOf>*</mirrorOf>
				      <name>Kagami Mirror</name>
				      <url>%s/artifacts/%s</url>
				    </mirror>
				  </mirrors>
				  -->
				</settings>""".formatted(servers, repositories, pluginRepositories, params.baseUrl(), primaryRepo);
		return new ConfigExample(title, filename, content);
	}

	private static ConfigExample gradleGroovyConfig(Params params) {
		List<String> repositoryIds = params.repositoryIds();
		boolean isHttpUrl = params.baseUrl().startsWith("http://");
		String title = "Gradle Configuration (Groovy DSL)";
		String filename = "$HOME/.gradle/init.gradle";
		String tokenValue = gradleTokenValue(params.token(), params.authMethod());
		if (repositoryIds.size() == 1) {
			String repo = repositoryIds.get(0);
			String content = """
					// $HOME/.gradle/init.gradle - Groovy DSL version

					def repoUrl = "%s/artifacts/%s"
					def repoToken = "%s"

					// For regular dependencies (legacy projects)
					allprojects {
					    repositories {
					        maven {
					            url = repoUrl%s%s
					        }
					        mavenCentral() // fallback
					    }
					}

					// Configure settings.gradle
					settingsEvaluated { settings ->
					    // For plugin resolution
					    settings.pluginManagement {
					        repositories {
					            maven {
					                url = repoUrl%s%s
					            }
					            gradlePluginPortal() // fallback
					            mavenCentral() // fallback
					        }
					    }

					    // Dependency resolution management (Gradle 6.8+)
					    settings.dependencyResolutionManagement {
					        // Ignore repositories defined in build.gradle
					        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

					        repositories {
					            maven {
					                url = repoUrl%s%s
					            }
					            mavenCentral() // fallback
					        }
					    }
					}""".formatted(params.baseUrl(), repo, tokenValue, gradleInsecure("            ", isHttpUrl),
					credentials(params, true, "            ", params.authMethod()),
					gradleInsecure("                ", isHttpUrl),
					credentials(params, true, "                ", params.authMethod()),
					gradleInsecure("                ", isHttpUrl),
					credentials(params, true, "                ", params.authMethod()));
			return new ConfigExample(title, filename, content);
		}
		String repoVars = repositoryIds.stream()
			.map(repo -> "def " + repo + "Url = \"" + params.baseUrl() + "/artifacts/" + repo + "\"")
			.collect(Collectors.joining("\n"));
		String repoBlocks = repositoryIds.stream()
			.map(repo -> "    repos.maven {\n" + "        url = " + repo + "Url" + gradleInsecure("        ", isHttpUrl)
					+ credentials(params, true, "        ", params.authMethod()) + "\n    }")
			.collect(Collectors.joining("\n    "));
		String content = """
				// $HOME/.gradle/init.gradle - Groovy DSL version

				// Repository configurations
				%s
				def repoToken = "%s"

				// Extension method to add repositories
				def addKagamiRepositories = { repos ->
				%s
				    repos.mavenCentral() // fallback
				}

				// For regular dependencies (legacy projects)
				allprojects {
				    repositories {
				        addKagamiRepositories(delegate)
				    }
				}

				// Configure settings.gradle
				settingsEvaluated { settings ->
				    // For plugin resolution
				    settings.pluginManagement {
				        repositories {
				            addKagamiRepositories(delegate)
				            gradlePluginPortal() // fallback
				        }
				    }

				    // Dependency resolution management (Gradle 6.8+)
				    settings.dependencyResolutionManagement {
				        // Ignore repositories defined in build.gradle
				        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

				        repositories {
				            addKagamiRepositories(delegate)
				        }
				    }
				}""".formatted(repoVars, tokenValue, repoBlocks);
		return new ConfigExample(title, filename, content);
	}

	private static ConfigExample gradleKotlinConfig(Params params) {
		List<String> repositoryIds = params.repositoryIds();
		boolean isHttpUrl = params.baseUrl().startsWith("http://");
		String title = "Gradle Configuration (Kotlin DSL)";
		String filename = "$HOME/.gradle/init.gradle.kts";
		String tokenValue = gradleTokenValue(params.token(), params.authMethod());
		String imports = params.authMethod() == AuthMethod.BEARER ? """
				import org.gradle.api.artifacts.repositories.PasswordCredentials
				import org.gradle.authentication.http.HttpHeaderAuthentication
				import org.gradle.kotlin.dsl.*

				""" : "";
		if (repositoryIds.size() == 1) {
			String repo = repositoryIds.get(0);
			String content = """
					// $HOME/.gradle/init.gradle.kts - Kotlin DSL version

					%sval repoUrl = "%s/artifacts/%s"
					val repoToken = "%s"

					// Extension function to configure repository
					fun RepositoryHandler.addKagamiRepository() {
					    maven {
					        url = uri(repoUrl)%s%s
					    }
					}

					// For regular dependencies (legacy projects)
					allprojects {
					    repositories {
					        addKagamiRepository()
					        mavenCentral() // fallback
					    }
					}

					// Configure settings.gradle
					settingsEvaluated {
					    // For plugin resolution
					    pluginManagement {
					        repositories {
					            addKagamiRepository()
					            gradlePluginPortal() // fallback
					            mavenCentral() // fallback
					        }
					    }

					    // Dependency resolution management (Gradle 6.8+)
					    dependencyResolutionManagement {
					        // Ignore repositories defined in build.gradle
					        @Suppress("UnstableApiUsage")
					        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

					        repositories {
					            addKagamiRepository()
					            mavenCentral() // fallback
					        }
					    }
					}""".formatted(imports, params.baseUrl(), repo, tokenValue,
					gradleKotlinInsecure("        ", isHttpUrl),
					credentials(params, false, "        ", params.authMethod()));
			return new ConfigExample(title, filename, content);
		}
		String repoVars = repositoryIds.stream()
			.map(repo -> "val " + repo + "Url = \"" + params.baseUrl() + "/artifacts/" + repo + "\"")
			.collect(Collectors.joining("\n"));
		String repoBlocks = repositoryIds.stream()
			.map(repo -> "    maven {\n" + "        url = uri(" + repo + "Url)"
					+ gradleKotlinInsecure("        ", isHttpUrl)
					+ credentials(params, false, "        ", params.authMethod()) + "\n    }")
			.collect(Collectors.joining("\n    "));
		String content = """
				// $HOME/.gradle/init.gradle.kts - Kotlin DSL version

				%s// Repository configurations
				%s
				val repoToken = "%s"

				// Extension function to configure Kagami repositories
				fun RepositoryHandler.addKagamiRepositories() {
				%s
				    mavenCentral() // fallback
				}

				// For regular dependencies (legacy projects)
				allprojects {
				    repositories {
				        addKagamiRepositories()
				    }
				}

				// Configure settings.gradle
				settingsEvaluated {
				    // For plugin resolution
				    pluginManagement {
				        repositories {
				            addKagamiRepositories()
				            gradlePluginPortal() // fallback
				        }
				    }

				    // Dependency resolution management (Gradle 6.8+)
				    dependencyResolutionManagement {
				        // Ignore repositories defined in build.gradle
				        @Suppress("UnstableApiUsage")
				        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

				        repositories {
				            addKagamiRepositories()
				        }
				    }
				}""".formatted(imports, repoVars, tokenValue, repoBlocks);
		return new ConfigExample(title, filename, content);
	}

}
