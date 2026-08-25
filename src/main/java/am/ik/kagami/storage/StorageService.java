package am.ik.kagami.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.core.io.Resource;

/**
 * Service interface for artifact storage operations
 */
public interface StorageService {

	/**
	 * Store an artifact from an input stream
	 * @param location the location of the artifact
	 * @param inputStream the input stream to read from
	 * @throws IOException if an I/O error occurs
	 */
	void store(ArtifactLocation location, InputStream inputStream) throws IOException;

	/**
	 * Retrieve an artifact as a Resource
	 * @param location the location of the artifact
	 * @return the artifact as a Resource, or {@link Optional#empty()} if not found
	 */
	Optional<Resource> retrieve(ArtifactLocation location);

	/**
	 * Delete an artifact or directory from storage
	 * @param location the location of the artifact or directory
	 * @return true if deletion was successful, false if the path didn't exist
	 * @throws IOException if an I/O error occurs during deletion
	 */
	boolean delete(ArtifactLocation location) throws IOException;

}
