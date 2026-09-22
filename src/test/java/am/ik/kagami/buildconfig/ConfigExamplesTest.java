package am.ik.kagami.buildconfig;

import java.util.List;

import am.ik.kagami.buildconfig.ConfigExamples.AuthMethod;
import am.ik.kagami.buildconfig.ConfigExamples.BuildTool;
import am.ik.kagami.buildconfig.ConfigExamples.ConfigExample;
import org.junit.jupiter.api.Test;

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
