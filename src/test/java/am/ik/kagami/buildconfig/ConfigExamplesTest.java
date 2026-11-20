package am.ik.kagami.buildconfig;

import java.util.List;

import am.ik.kagami.buildconfig.ConfigExamples.AuthMethod;
import am.ik.kagami.buildconfig.ConfigExamples.BuildTool;
import am.ik.kagami.buildconfig.ConfigExamples.ConfigExample;
import am.ik.kagami.buildconfig.ConfigExampleCatalog.Catalog;
import am.ik.kagami.buildconfig.ConfigExampleCatalog.Pane;
import am.ik.kagami.buildconfig.ConfigExampleCatalog.Selection;
import am.ik.kagami.buildconfig.ConfigExampleCatalog.ToolTab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConfigExamples}, the Java port of the configuration example generators
 * that used to live in the client side application.
 */
class ConfigExamplesTest {

	private ConfigExamples.Params params(String token) {
		return ConfigExamples.Params.builder()
			.repositoryIds(List.of("snapshot"))
			.token(token)
			.baseUrl("https://kagami.example.com")
			.isPrivate(true)
			.authMethod(AuthMethod.BASIC)
			.build();
	}

	@Test
	void mavenSingleRepositoryBasicAuth() {
		ConfigExample example = ConfigExamples.generate(BuildTool.MAVEN, params("jwt-token"));
		assertThat(example.title()).isEqualTo("Maven Configuration");
		assertThat(example.filename()).isEqualTo("$HOME/.m2/settings.xml");
		assertThat(example.content()).contains("""
				<server>
				      <id>kagami-snapshot</id>
				      <username>kagami</username>
				      <password>jwt-token</password>
				    </server>""");
		assertThat(example.content()).contains("<url>https://kagami.example.com/artifacts/snapshot</url>");
		assertThat(example.content())
			.contains("<!-- The username can be any value. The password is the JWT token. -->");
	}

	@Test
	void mavenSingleRepositoryBearerAuth() {
		ConfigExample example = ConfigExamples.generate(BuildTool.MAVEN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("snapshot"))
					.token("jwt-token")
					.baseUrl("https://kagami.example.com")
					.isPrivate(true)
					.authMethod(AuthMethod.BEARER)
					.build());
		assertThat(example.content()).contains("<value>Bearer jwt-token</value>");
		assertThat(example.content()).doesNotContain("<password>");
	}

	@Test
	void mavenMultipleRepositories() {
		ConfigExample example = ConfigExamples.generate(BuildTool.MAVEN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("release", "snapshot"))
					.token("jwt-token")
					.baseUrl("https://kagami.example.com")
					.isPrivate(true)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).contains("<id>kagami-release</id>", "<id>kagami-snapshot</id>",
				"<id>kagami-multiple</id>", "kagami-all");
	}

	@Test
	void mavenPublicRepositoryHasNoServerSection() {
		ConfigExample example = ConfigExamples.generate(BuildTool.MAVEN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("central"))
					.token("")
					.baseUrl("https://kagami.example.com")
					.isPrivate(false)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).doesNotContain("<servers>");
	}

	@Test
	void gradleGroovySingleRepository() {
		ConfigExample example = ConfigExamples.generate(BuildTool.GRADLE_GROOVY, params("jwt-token"));
		assertThat(example.title()).isEqualTo("Gradle Configuration (Groovy DSL)");
		assertThat(example.filename()).isEqualTo("$HOME/.gradle/init.gradle");
		assertThat(example.content()).contains("def repoUrl = \"https://kagami.example.com/artifacts/snapshot\"");
		assertThat(example.content()).contains("def repoToken = \"jwt-token\"");
		assertThat(example.content()).contains("username = \"kagami\" // can be any value");
		assertThat(example.content()).doesNotContain("allowInsecureProtocol");
	}

	@Test
	void gradleKotlinSingleRepositoryBearerAuth() {
		ConfigExample example = ConfigExamples.generate(BuildTool.GRADLE_KOTLIN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("snapshot"))
					.token("jwt-token")
					.baseUrl("https://kagami.example.com")
					.isPrivate(true)
					.authMethod(AuthMethod.BEARER)
					.build());
		assertThat(example.title()).isEqualTo("Gradle Configuration (Kotlin DSL)");
		assertThat(example.filename()).isEqualTo("$HOME/.gradle/init.gradle.kts");
		assertThat(example.content()).contains("import org.gradle.kotlin.dsl.*");
		assertThat(example.content()).contains("create<HttpHeaderAuthentication>(\"header\")");
		assertThat(example.content()).contains("val repoToken = \"Bearer jwt-token\"");
	}

	@Test
	void gradleKotlinHttpUrlAddsInsecureProtocolFlag() {
		ConfigExample example = ConfigExamples.generate(BuildTool.GRADLE_KOTLIN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("snapshot"))
					.token("jwt-token")
					.baseUrl("http://kagami.example.com")
					.isPrivate(true)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).contains("isAllowInsecureProtocol = true");
	}

	@Test
	void gradlePublicRepositoryHasNoCredentials() {
		for (BuildTool tool : BuildTool.values()) {
			ConfigExample example = ConfigExamples.generate(tool,
					ConfigExamples.Params.builder()
						.repositoryIds(List.of("central"))
						.token("jwt-token")
						.baseUrl("https://kagami.example.com")
						.isPrivate(false)
						.authMethod(AuthMethod.BASIC)
						.build());
			assertThat(example.content()).doesNotContain("credentials");
			assertThat(example.content()).doesNotContain("authentication");
		}
	}

	@Test
	void mavenMultiplePublicRepositoriesKeepIndentation() {
		ConfigExample example = ConfigExamples.generate(BuildTool.MAVEN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("release", "snapshot"))
					.token("")
					.baseUrl("https://kagami.example.com")
					.isPrivate(false)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).contains("""
				<repositories>
				        <repository>
				          <id>kagami-release</id>""");
		assertThat(example.content()).contains("""
				<pluginRepositories>
				        <pluginRepository>
				          <id>kagami-release</id>""");
	}

	@Test
	void npmConfigurationPointsAtTheMirror() {
		ConfigExample example = ConfigExamples.generate(BuildTool.NPM, params("jwt-token"));
		assertThat(example.title()).isEqualTo("npm Configuration");
		assertThat(example.filename()).isEqualTo("$HOME/.npmrc");
		assertThat(example.content()).contains("registry=https://kagami.example.com/artifacts/snapshot/");
		assertThat(example.content()).contains("//kagami.example.com/artifacts/snapshot/:_authToken=jwt-token");
	}

	@Test
	void npmScopedRepositoryAddsScopedRegistry() {
		ConfigExample example = ConfigExamples.generate(BuildTool.NPM,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("@acme"))
					.token("jwt-token")
					.baseUrl("https://kagami.example.com")
					.isPrivate(true)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).contains("@acme:registry=https://kagami.example.com/artifacts/@acme/");
	}

	@Test
	void pipConfigurationEmbedsCredentialsAndAddsTrustedHost() {
		ConfigExample example = ConfigExamples.generate(BuildTool.PIP,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("pypi", "release"))
					.token("jwt-token")
					.baseUrl("http://kagami.example.com:8080")
					.isPrivate(true)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.filename()).isEqualTo("$HOME/.config/pip/pip.conf");
		assertThat(example.content())
			.contains("index-url = http://kagami:jwt-token@kagami.example.com:8080/artifacts/pypi/simple/");
		assertThat(example.content())
			.contains("extra-index-url = http://kagami:jwt-token@kagami.example.com:8080/artifacts/release/simple/");
		assertThat(example.content()).contains("trusted-host = kagami.example.com:8080");
	}

	@Test
	void pipKeyringConfigurationCarriesTheUserNameOnly() {
		ConfigExample example = ConfigExamples.generate(BuildTool.PIP_KEYRING, params("jwt-token"));
		assertThat(example.content())
			.contains("index-url = https://kagami@kagami.example.com/artifacts/snapshot/simple/");
		assertThat(example.content()).contains("keyring set kagami.example.com kagami");
		assertThat(example.content()).doesNotContain("jwt-token");
	}

	@Test
	void uvEnvironmentConfigurationPointsAtTheMirror() {
		ConfigExample example = ConfigExamples.generate(BuildTool.UV_ENV, params("jwt-token"));
		assertThat(example.content())
			.contains("export UV_INDEX_URL=\"https://kagami:jwt-token@kagami.example.com/artifacts/snapshot/simple/\"");
	}

	@Test
	void uvPyprojectConfigurationPointsAtTheMirror() {
		ConfigExample example = ConfigExamples.generate(BuildTool.UV_PYPROJECT, params("jwt-token"));
		assertThat(example.filename()).isEqualTo("pyproject.toml");
		assertThat(example.content()).contains("[[tool.uv.index]]", "name = \"kagami-snapshot\"",
				"url = \"https://kagami.example.com/artifacts/snapshot/simple/\"", "default = true",
				"UV_INDEX_KAGAMI_SNAPSHOT_PASSWORD");
	}

	@ParameterizedTest
	@EnumSource(value = BuildTool.class, names = { "NPM", "PIP", "PIP_KEYRING", "UV_ENV", "UV_PYPROJECT" })
	void packageToolsOfPublicRepositoryHaveNoCredentials(BuildTool tool) {
		ConfigExample example = ConfigExamples.generate(tool,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("central"))
					.token("jwt-token")
					.baseUrl("https://kagami.example.com")
					.isPrivate(false)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).doesNotContain("jwt-token");
	}

	@Test
	void catalogOfNpmSelectionShowsOnlyNpmExamples() {
		Catalog catalog = ConfigExampleCatalog.build(List.of(ConfigExampleCatalog.selection("npm-repo", "npm", true)),
				"jwt-token", "https://kagami.example.com");
		assertThat(catalog.panes()).extracting(Pane::paneId).containsExactly("npm");
		assertThat(catalog.toolTabs()).extracting(ToolTab::label).containsExactly("npm");
		assertThat(catalog.toolTabs().get(0).active()).isTrue();
		assertThat(catalog.authTabs()).isFalse();
		assertThat(catalog.panes().get(0).content()).doesNotContain("$HOME/.m2/settings.xml");
	}

	@Test
	void catalogOfPypiSelectionShowsPipAndUvExamples() {
		Catalog catalog = ConfigExampleCatalog.build(List.of(ConfigExampleCatalog.selection("pypi-repo", "pypi", true)),
				"jwt-token", "https://kagami.example.com");
		assertThat(catalog.panes()).extracting(Pane::paneId)
			.containsExactly("pip", "pipkeyring", "uvenv", "uvpyproject");
		assertThat(catalog.toolTabs()).extracting(ToolTab::label).containsExactly("pip", "uv");
		assertThat(catalog.authTabs()).isFalse();
	}

	@Test
	void catalogOfPublicPypiSelectionOmitsKeyring() {
		Catalog catalog = ConfigExampleCatalog.build(
				List.of(ConfigExampleCatalog.selection("pypi-repo", "pypi", false)), "", "https://kagami.example.com");
		assertThat(catalog.panes()).extracting(Pane::paneId).containsExactly("pip", "uvenv", "uvpyproject");
	}

	@Test
	void catalogOfMavenSelectionKeepsMavenTabsAndAuthMethods() {
		Catalog catalog = ConfigExampleCatalog.build(
				List.of(ConfigExampleCatalog.selection("test-repo", "maven", true)), "jwt-token",
				"https://kagami.example.com");
		assertThat(catalog.panes()).extracting(Pane::paneId)
			.containsExactly("maven-basic", "maven-bearer", "gradlegroovy-basic", "gradlegroovy-bearer",
					"gradlekotlin-basic", "gradlekotlin-bearer");
		assertThat(catalog.toolTabs()).extracting(ToolTab::pattern)
			.containsExactly("maven-*", "gradlegroovy-*", "gradlekotlin-*");
		assertThat(catalog.authTabs()).isTrue();
		assertThat(catalog.panes().get(0).active()).isTrue();
	}

	@Test
	void catalogOfPublicMavenSelectionHasNoAuthMethods() {
		Catalog catalog = ConfigExampleCatalog.build(List.of(ConfigExampleCatalog.selection("central", "maven", false)),
				"", "https://kagami.example.com");
		assertThat(catalog.panes()).extracting(Pane::paneId)
			.containsExactly("maven-basic", "gradlegroovy-basic", "gradlekotlin-basic");
		assertThat(catalog.authTabs()).isFalse();
	}

	@Test
	void catalogOfMixedFormatsShowsEveryApplicableToolWithoutAuthTabs() {
		List<Selection> selections = List.of(ConfigExampleCatalog.selection("test-repo", "maven", true),
				ConfigExampleCatalog.selection("npm-repo", "npm", true));
		Catalog catalog = ConfigExampleCatalog.build(selections, "jwt-token", "https://kagami.example.com");
		assertThat(catalog.panes()).extracting(Pane::paneId)
			.containsExactly("maven-basic", "gradlegroovy-basic", "gradlekotlin-basic", "npm");
		assertThat(catalog.toolTabs()).extracting(ToolTab::label)
			.containsExactly("Maven", "Gradle (Groovy)", "Gradle (Kotlin)", "npm");
		assertThat(catalog.authTabs()).isFalse();
	}

	@Test
	void gradleMultipleRepositories() {
		ConfigExample example = ConfigExamples.generate(BuildTool.GRADLE_KOTLIN,
				ConfigExamples.Params.builder()
					.repositoryIds(List.of("release", "snapshot"))
					.token("jwt-token")
					.baseUrl("https://kagami.example.com")
					.isPrivate(true)
					.authMethod(AuthMethod.BASIC)
					.build());
		assertThat(example.content()).contains("val releaseUrl = \"https://kagami.example.com/artifacts/release\"",
				"val snapshotUrl = \"https://kagami.example.com/artifacts/snapshot\"", "addKagamiRepositories()");
	}

}
