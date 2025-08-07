package am.ik.kagami.storage;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Service interface for artifact storage operations
 */
public interface StorageService {

	/**
	 * Store an artifact from an input stream
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact
	 * @param inputStream the input stream to read from
	 * @throws IOException if an I/O error occurs
	 */
	void store(String repositoryId, String artifactPath, InputStream inputStream) throws IOException;

	/**
	 * Retrieve an artifact as a Resource
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact
	 * @return the artifact as a Resource, or null if not found
	 */
	Resource retrieve(String repositoryId, String artifactPath);

	/**
	 * Delete an artifact or directory from storage
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact or directory
	 * @return true if deletion was successful, false if the path didn't exist
	 * @throws IOException if an I/O error occurs during deletion
	 */
	boolean delete(String repositoryId, String artifactPath) throws IOException;

}