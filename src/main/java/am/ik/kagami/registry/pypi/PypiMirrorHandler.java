package am.ik.kagami.registry.pypi;

import am.ik.kagami.registry.Format;
import am.ik.kagami.registry.StoredArtifactMirrorHandler;
import am.ik.kagami.storage.StorageService;
import org.springframework.stereotype.Component;

/**
 * Serves PyPI repositories.
 * <p>
 * The format seam is in place; simple index fetching and link rewriting are added by the
 * PyPI mirror item. Until then the handler serves artifacts that are already stored.
 */
@Component
public class PypiMirrorHandler extends StoredArtifactMirrorHandler {

	public PypiMirrorHandler(StorageService storageService) {
		super(storageService);
	}

	@Override
	public Format format() {
		return Format.PYPI;
	}

}
