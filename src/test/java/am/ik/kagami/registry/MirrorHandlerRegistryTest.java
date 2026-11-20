package am.ik.kagami.registry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.AuthenticationType;
import am.ik.kagami.KagamiProperties.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that the registry binds repositories to their format handler and rejects invalid
 * format configuration at startup.
 */
class MirrorHandlerRegistryTest {

	private static final MirrorHandler MAVEN = handler(Format.MAVEN);

	private static final MirrorHandler NPM = handler(Format.NPM);

	private static final MirrorHandler PYPI = handler(Format.PYPI);

	@Test
	void formatSelectsTheHandler() {
		MirrorHandlerRegistry registry = registry(
				Map.of("npm", Repository.builder().url("https://example.com").format("npm").build(), "pypi",
						Repository.builder().url("https://example.com").format("pypi").build()));

		assertThat(registry.find("npm")).containsSame(NPM);
		assertThat(registry.find("pypi")).containsSame(PYPI);
	}

	@Test
	void defaultFormatIsMaven() {
		MirrorHandlerRegistry registry = registry(
				Map.of("repo", Repository.builder().url("https://example.com").build()));

		assertThat(registry.find("repo")).containsSame(MAVEN);
	}

	@Test
	void unknownFormatIsRejected() {
		Repository repository = Repository.builder().url("https://example.com").format("cargo").build();

		assertThatThrownBy(() -> registry(Map.of("repo", repository))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cargo");
	}

	@Test
	void sigstoreOnNonMavenFormatIsRejected() {
		Repository repository = Repository.builder()
			.url("https://example.com")
			.format("npm")
			.sigstore(KagamiProperties.Sigstore.builder().enabled(true).build())
			.build();

		assertThatThrownBy(() -> registry(Map.of("repo", repository))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("sigstore.enabled");
	}

	private static MirrorHandlerRegistry registry(Map<String, Repository> repositories) {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp/kagami").build())
			.repositories(repositories)
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(AuthenticationType.SIMPLE, List.of()))
			.build();
		return new MirrorHandlerRegistry(properties, List.of(MAVEN, NPM, PYPI));
	}

	private static MirrorHandler handler(Format format) {
		return new MirrorHandler() {
			@Override
			public Format format() {
				return format;
			}

			@Override
			public ResponseEntity<Resource> handle(MirrorRequest request) throws IOException {
				return ResponseEntity.notFound().build();
			}
		};
	}

}
