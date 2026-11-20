package am.ik.kagami.registry.npm;

import am.ik.kagami.registry.Format;
import am.ik.kagami.registry.StoredArtifactMirrorHandler;
import am.ik.kagami.storage.StorageService;
import org.springframework.stereotype.Component;

/**
 * Serves npm repositories.
 * <p>
 * The format seam is in place; packument fetching and {@code dist.tarball} rewriting are
 * added by the npm mirror item. Until then the handler serves artifacts that are already
 * stored.
 */
@Component
public class NpmMirrorHandler extends StoredArtifactMirrorHandler {

	public NpmMirrorHandler(StorageService storageService) {
		super(storageService);
	}

	@Override
	public Format format() {
		return Format.NPM;
	}

}
