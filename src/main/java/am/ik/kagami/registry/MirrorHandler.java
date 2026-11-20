package am.ik.kagami.registry;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

/**
 * Handles mirror requests for one repository format.
 * <p>
 * Implementations own everything that differs between formats: how an artifact path maps
 * to storage, whether an upstream fetch is needed, and how the response is transformed.
 * They share the routing namespace {@code /artifacts/{repositoryId}/**}, the storage
 * interface and the {@code ArtifactLocation} path validation.
 * <p>
 * A handler must validate the request path through {@code ArtifactLocation} before it
 * reads or writes storage; an {@link IllegalArgumentException} is turned into a 400
 * response by the router.
 */
public interface MirrorHandler {

	/**
	 * The format this handler serves.
	 * @return the format
	 */
	Format format();

	/**
	 * Serve a mirrored request.
	 * @param request the decoded and routed request
	 * @return the response, including status, headers and body
	 * @throws IOException if reading the stored artifact fails
	 */
	ResponseEntity<Resource> handle(MirrorRequest request) throws IOException;

}
