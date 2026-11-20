package am.ik.kagami.registry;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.Repository;
import org.springframework.stereotype.Component;

/**
 * Binds every configured repository to the {@link MirrorHandler} for its format.
 * <p>
 * The binding is resolved once, when the application starts, so that a misconfigured
 * format or a format-specific setting is reported at startup instead of failing the first
 * request.
 */
@Component
public class MirrorHandlerRegistry {

	private final Map<String, MirrorHandler> handlersByRepository;

	public MirrorHandlerRegistry(KagamiProperties properties, List<MirrorHandler> mirrorHandlers) {
		Map<Format, MirrorHandler> handlersByFormat = new EnumMap<>(Format.class);
		for (MirrorHandler handler : mirrorHandlers) {
			MirrorHandler previous = handlersByFormat.put(handler.format(), handler);
			if (previous != null) {
				throw new IllegalStateException(
						"Multiple mirror handlers registered for format " + handler.format().value());
			}
		}
		Map<String, MirrorHandler> resolved = new HashMap<>();
		properties.repositories().forEach((repositoryId, repository) -> {
			Format format = resolve(repositoryId, repository);
			MirrorHandler handler = handlersByFormat.get(format);
			if (handler == null) {
				throw new IllegalStateException("No mirror handler registered for format " + format.value());
			}
			validate(repositoryId, repository, format);
			resolved.put(repositoryId, handler);
		});
		this.handlersByRepository = Map.copyOf(resolved);
	}

	/**
	 * Find the handler bound to a repository.
	 * @param repositoryId the repository identifier
	 * @return the handler, or an empty optional if the repository is not configured
	 */
	public Optional<MirrorHandler> find(String repositoryId) {
		return Optional.ofNullable(this.handlersByRepository.get(repositoryId));
	}

	private static Format resolve(String repositoryId, Repository repository) {
		try {
			return Format.parse(repository.format());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException(
					"Invalid 'kagami.repositories.%s.format': %s".formatted(repositoryId, e.getMessage()), e);
		}
	}

	/**
	 * Reject format-specific settings that do not apply to the configured format. The
	 * sigstore sidecar bundles are a Maven artifact convention, so they cannot be enabled
	 * on an npm or PyPI repository.
	 */
	private static void validate(String repositoryId, Repository repository, Format format) {
		if (format != Format.MAVEN && repository.sigstore().enabled()) {
			throw new IllegalStateException(
					"'kagami.repositories.%s.sigstore.enabled' is only supported for maven repositories"
						.formatted(repositoryId));
		}
	}

}
