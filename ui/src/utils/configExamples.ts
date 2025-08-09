// Shared configuration examples for build tools

export interface ConfigExample {
  title: string;
  content: string;
  filename: string;
}

export interface ConfigExamplesParams {
  repositoryIds: string[];
  token: string;
  baseUrl: string;
  isPrivate?: boolean;
}

export function generateMavenConfig({ repositoryIds, token, baseUrl, isPrivate = true }: ConfigExamplesParams): ConfigExample {
  const primaryRepo = repositoryIds[0];
  
  if (repositoryIds.length === 1) {
    return {
      title: 'Maven Configuration',
      filename: 'settings.xml',
      content: `<!-- RECOMMENDED: Add to your settings.xml -->
<settings>
${isPrivate ? `  <servers>
    <server>
      <id>kagami-${primaryRepo}</id>
      <configuration>
        <httpHeaders>
          <property>
            <name>Authorization</name>
            <value>Bearer ${token}</value>
          </property>
        </httpHeaders>
      </configuration>
    </server>
  </servers>
` : ''}  <profiles>
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
      filename: 'settings.xml',
      content: `<!-- RECOMMENDED: Add to your settings.xml -->
<settings>
${isPrivate ? `  <servers>
${repositoryIds.map(repo => `    <server>
      <id>kagami-${repo}</id>
      <configuration>
        <httpHeaders>
          <property>
            <name>Authorization</name>
            <value>Bearer ${token}</value>
          </property>
        </httpHeaders>
      </configuration>
    </server>`).join('\n')}
  </servers>
` : ''}  <profiles>
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

export function generateGradleGroovyConfig({ repositoryIds, token, baseUrl, isPrivate = true }: ConfigExamplesParams): ConfigExample {
  const primaryRepo = repositoryIds[0];
  
  if (repositoryIds.length === 1) {
    return {
      title: 'Gradle Configuration (Groovy DSL)',
      filename: 'build.gradle',
      content: `repositories {
    maven {
        name = "${primaryRepo}"
        url = "${baseUrl}/artifacts/${primaryRepo}"${isPrivate ? `
        metadataSources {
            mavenPom()
            artifact()
        }
        authentication {
            header(HttpHeaderAuthentication)
        }
        credentials(HttpHeaderCredentials) {
            name = "Authorization"
            value = "Bearer ${token}"
        }` : ''}
    }
    
    // You can also add it as the first repository for priority
    // maven { 
    //     url "${baseUrl}/artifacts/${primaryRepo}"${isPrivate ? `
    //     authentication {
    //         header(HttpHeaderAuthentication)
    //     }
    //     credentials(HttpHeaderCredentials) {
    //         name = "Authorization"
    //         value = "Bearer ${token}"
    //     }` : ''}
    // }
    // mavenCentral() // fallback
}`
    };
  } else {
    return {
      title: 'Gradle Configuration (Groovy DSL)',
      filename: 'build.gradle',
      content: `repositories {
${repositoryIds.map(repo => `    maven {
        name = "${repo}"
        url = "${baseUrl}/artifacts/${repo}"${isPrivate ? `
        metadataSources {
            mavenPom()
            artifact()
        }
        authentication {
            header(HttpHeaderAuthentication)
        }
        credentials(HttpHeaderCredentials) {
            name = "Authorization"
            value = "Bearer ${token}"
        }` : ''}
    }`).join('\n')}
}`
    };
  }
}

export function generateGradleKotlinConfig({ repositoryIds, token, baseUrl, isPrivate = true }: ConfigExamplesParams): ConfigExample {
  const primaryRepo = repositoryIds[0];
  
  if (repositoryIds.length === 1) {
    return {
      title: 'Gradle Configuration (Kotlin DSL)',
      filename: 'build.gradle.kts',
      content: `repositories {
    maven {
        name = "${primaryRepo}"
        url = uri("${baseUrl}/artifacts/${primaryRepo}")${isPrivate ? `
        metadataSources {
            mavenPom()
            artifact()
        }
        authentication {
            create<HttpHeaderAuthentication>("header")
        }
        credentials<HttpHeaderCredentials>("header") {
            name = "Authorization"
            value = "Bearer ${token}"
        }` : ''}
    }
    
    // You can also add it as the first repository for priority
    // maven { 
    //     url = uri("${baseUrl}/artifacts/${primaryRepo}")${isPrivate ? `
    //     authentication {
    //         create<HttpHeaderAuthentication>("header")
    //     }
    //     credentials<HttpHeaderCredentials>("header") {
    //         name = "Authorization"
    //         value = "Bearer ${token}"
    //     }` : ''}
    // }
    // mavenCentral() // fallback
}`
    };
  } else {
    return {
      title: 'Gradle Configuration (Kotlin DSL)',
      filename: 'build.gradle.kts',
      content: `repositories {
${repositoryIds.map(repo => `    maven {
        name = "${repo}"
        url = uri("${baseUrl}/artifacts/${repo}")${isPrivate ? `
        metadataSources {
            mavenPom()
            artifact()
        }
        authentication {
            create<HttpHeaderAuthentication>("header")
        }
        credentials<HttpHeaderCredentials>("header") {
            name = "Authorization"
            value = "Bearer ${token}"
        }` : ''}
    }`).join('\n')}
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