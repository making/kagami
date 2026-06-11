// Shared configuration examples for build tools

export interface ConfigExample {
  title: string;
  content: string;
  filename: string;
}

// 'basic': standard username/password credentials (username is arbitrary, password is the JWT token)
// 'bearer': Authorization Bearer header with the JWT token
export type AuthMethod = 'basic' | 'bearer';

export interface ConfigExamplesParams {
  repositoryIds: string[];
  token: string;
  baseUrl: string;
  isPrivate?: boolean;
  authMethod?: AuthMethod;
}

function mavenServer(repo: string, token: string, authMethod: AuthMethod): string {
  if (authMethod === 'bearer') {
    return `    <server>
      <id>kagami-${repo}</id>
      <configuration>
        <httpHeaders>
          <property>
            <name>Authorization</name>
            <value>Bearer ${token}</value>
          </property>
        </httpHeaders>
      </configuration>
    </server>`;
  }
  return `    <server>
      <id>kagami-${repo}</id>
      <username>kagami</username>
      <password>${token}</password>
    </server>`;
}

function mavenServersSection(repositoryIds: string[], token: string, authMethod: AuthMethod): string {
  const comment = authMethod === 'basic'
    ? '    <!-- The username can be any value. The password is the JWT token. -->\n'
    : '';
  return `  <servers>
${comment}${repositoryIds.map(repo => mavenServer(repo, token, authMethod)).join('\n')}
  </servers>
`;
}

function gradleGroovyCredentials(indent: string, authMethod: AuthMethod): string {
  if (authMethod === 'bearer') {
    return `
${indent}authentication {
${indent}    header(HttpHeaderAuthentication)
${indent}}
${indent}credentials(HttpHeaderCredentials) {
${indent}    name = "Authorization"
${indent}    value = repoToken
${indent}}`;
  }
  return `
${indent}credentials {
${indent}    username = "kagami" // can be any value
${indent}    password = repoToken
${indent}}`;
}

function gradleKotlinCredentials(indent: string, authMethod: AuthMethod): string {
  if (authMethod === 'bearer') {
    return `
${indent}authentication {
${indent}    create<HttpHeaderAuthentication>("header")
${indent}}
${indent}credentials(HttpHeaderCredentials::class) {
${indent}    name = "Authorization"
${indent}    value = repoToken
${indent}}`;
  }
  return `
${indent}credentials {
${indent}    username = "kagami" // can be any value
${indent}    password = repoToken
${indent}}`;
}

function gradleTokenValue(token: string, authMethod: AuthMethod): string {
  return authMethod === 'bearer' ? `Bearer ${token}` : token;
}

const GRADLE_KOTLIN_BEARER_IMPORTS = `import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication
import org.gradle.kotlin.dsl.*

`;

export function generateMavenConfig({ repositoryIds, token, baseUrl, isPrivate = true, authMethod = 'basic' }: ConfigExamplesParams): ConfigExample {
  const primaryRepo = repositoryIds[0];

  if (repositoryIds.length === 1) {
    return {
      title: 'Maven Configuration',
      filename: '$HOME/.m2/settings.xml',
      content: `<!-- RECOMMENDED: Add to your $HOME/.m2/settings.xml -->
<settings>
${isPrivate ? mavenServersSection([primaryRepo], token, authMethod) : ''}  <profiles>
    <profile>
      <id>kagami-${primaryRepo}</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <repositories>
        <repository>
          <id>kagami-${primaryRepo}</id>
          <name>Kagami Repository - ${primaryRepo}</name>
          <url>${baseUrl}/artifacts/${primaryRepo}</url>
          <snapshots>
            <enabled>${primaryRepo.includes('snapshot') ? 'true' : 'true'}</enabled>
          </snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>kagami-${primaryRepo}</id>
          <name>Kagami Repository - ${primaryRepo}</name>
          <url>${baseUrl}/artifacts/${primaryRepo}</url>
          <snapshots>
            <enabled>${primaryRepo.includes('snapshot') ? 'true' : 'true'}</enabled>
          </snapshots>
        </pluginRepository>
      </pluginRepositories>
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
      <id>kagami-${primaryRepo}</id>
      <mirrorOf>*</mirrorOf>
      <name>Kagami Mirror - ${primaryRepo}</name>
      <url>${baseUrl}/artifacts/${primaryRepo}</url>
    </mirror>
  </mirrors>
  -->

  <!-- SIMPLE: Add directly to your pom.xml (project-specific) -->
  <!--
  <repositories>
    <repository>
      <id>kagami-${primaryRepo}</id>
      <name>${primaryRepo.charAt(0).toUpperCase() + primaryRepo.slice(1)} Repository</name>
      <url>${baseUrl}/artifacts/${primaryRepo}</url>
    </repository>
  </repositories>
  -->
</settings>`
    };
  } else {
    return {
      title: 'Maven Configuration',
      filename: '$HOME/.m2/settings.xml',
      content: `<!-- RECOMMENDED: Add to your $HOME/.m2/settings.xml -->
<settings>
${isPrivate ? mavenServersSection(repositoryIds, token, authMethod) : ''}  <profiles>
    <profile>
      <id>kagami-multiple</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <repositories>
${repositoryIds.map(repo => `        <repository>
          <id>kagami-${repo}</id>
          <name>Kagami Repository - ${repo}</name>
          <url>${baseUrl}/artifacts/${repo}</url>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
        </repository>`).join('\n')}
      </repositories>
      <pluginRepositories>
${repositoryIds.map(repo => `        <pluginRepository>
          <id>kagami-${repo}</id>
          <name>Kagami Repository - ${repo}</name>
          <url>${baseUrl}/artifacts/${repo}</url>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
        </pluginRepository>`).join('\n')}
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
      <url>${baseUrl}/artifacts/${primaryRepo}</url>
    </mirror>
  </mirrors>
  -->
</settings>`
    };
  }
}

export function generateGradleGroovyConfig({ repositoryIds, token, baseUrl, isPrivate = true, authMethod = 'basic' }: ConfigExamplesParams): ConfigExample {
  const isHttpUrl = baseUrl.startsWith('http://');

  if (repositoryIds.length === 1) {
    const repo = repositoryIds[0];
    return {
      title: 'Gradle Configuration (Groovy DSL)',
      filename: '$HOME/.gradle/init.gradle',
      content: `// $HOME/.gradle/init.gradle - Groovy DSL version

def repoUrl = "${baseUrl}/artifacts/${repo}"
def repoToken = "${gradleTokenValue(token, authMethod)}"

// For regular dependencies (legacy projects)
allprojects {
    repositories {
        maven {
            url = repoUrl${isHttpUrl ? `
            allowInsecureProtocol = true` : ''}${isPrivate ? gradleGroovyCredentials('            ', authMethod) : ''}
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
                url = repoUrl${isHttpUrl ? `
                allowInsecureProtocol = true` : ''}${isPrivate ? gradleGroovyCredentials('                ', authMethod) : ''}
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
                url = repoUrl${isHttpUrl ? `
                allowInsecureProtocol = true` : ''}${isPrivate ? gradleGroovyCredentials('                ', authMethod) : ''}
            }
            mavenCentral() // fallback
        }
    }
}`
    };
  } else {
    return {
      title: 'Gradle Configuration (Groovy DSL)',
      filename: '$HOME/.gradle/init.gradle',
      content: `// $HOME/.gradle/init.gradle - Groovy DSL version

// Repository configurations
${repositoryIds.map(repo => `def ${repo}Url = "${baseUrl}/artifacts/${repo}"`).join('\n')}
def repoToken = "${gradleTokenValue(token, authMethod)}"

// Extension method to add repositories
def addKagamiRepositories = { repos ->
${repositoryIds.map(repo => `    repos.maven {
        url = ${repo}Url${isHttpUrl ? `
        allowInsecureProtocol = true` : ''}${isPrivate ? gradleGroovyCredentials('        ', authMethod) : ''}
    }`).join('\n    ')}
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
}`
    };
  }
}

export function generateGradleKotlinConfig({ repositoryIds, token, baseUrl, isPrivate = true, authMethod = 'basic' }: ConfigExamplesParams): ConfigExample {
  const isHttpUrl = baseUrl.startsWith('http://');
  const imports = authMethod === 'bearer' ? GRADLE_KOTLIN_BEARER_IMPORTS : '';

  if (repositoryIds.length === 1) {
    const repo = repositoryIds[0];
    return {
      title: 'Gradle Configuration (Kotlin DSL)',
      filename: '$HOME/.gradle/init.gradle.kts',
      content: `// $HOME/.gradle/init.gradle.kts - Kotlin DSL version

${imports}val repoUrl = "${baseUrl}/artifacts/${repo}"
val repoToken = "${gradleTokenValue(token, authMethod)}"

// Extension function to configure repository
fun RepositoryHandler.addKagamiRepository() {
    maven {
        url = uri(repoUrl)${isHttpUrl ? `
        isAllowInsecureProtocol = true` : ''}${isPrivate ? gradleKotlinCredentials('        ', authMethod) : ''}
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
}`
    };
  } else {
    return {
      title: 'Gradle Configuration (Kotlin DSL)',
      filename: '$HOME/.gradle/init.gradle.kts',
      content: `// $HOME/.gradle/init.gradle.kts - Kotlin DSL version

${imports}// Repository configurations
${repositoryIds.map(repo => `val ${repo}Url = "${baseUrl}/artifacts/${repo}"`).join('\n')}
val repoToken = "${gradleTokenValue(token, authMethod)}"

// Extension function to configure Kagami repositories
fun RepositoryHandler.addKagamiRepositories() {
${repositoryIds.map(repo => `    maven {
        url = uri(${repo}Url)${isHttpUrl ? `
        isAllowInsecureProtocol = true` : ''}${isPrivate ? gradleKotlinCredentials('        ', authMethod) : ''}
    }`).join('\n    ')}
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
}`
    };
  }
}

export type BuildTool = 'maven' | 'gradleGroovy' | 'gradleKotlin';

export function generateConfigExample(tool: BuildTool, params: ConfigExamplesParams): ConfigExample {
  switch (tool) {
    case 'maven':
      return generateMavenConfig(params);
    case 'gradleGroovy':
      return generateGradleGroovyConfig(params);
    case 'gradleKotlin':
      return generateGradleKotlinConfig(params);
    default:
      throw new Error(`Unknown build tool: ${tool}`);
  }
}
