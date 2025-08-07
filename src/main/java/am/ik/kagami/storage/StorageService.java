package am.ik.kagami.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

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
	 * @return the path where the artifact was stored
	 * @throws IOException if an I/O error occurs
	 */
	Path store(String repositoryId, String artifactPath, InputStream inputStream) throws IOException;

	/**
	 * Retrieve an artifact as a Resource
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact
	 * @return the artifact as a Resource, or null if not found
	 */
	Resource retrieve(String repositoryId, String artifactPath);

	/**
	 * Check if an artifact exists in storage
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact
	 * @return true if the artifact exists, false otherwise
	 */
	boolean exists(String repositoryId, String artifactPath);

	/**
	 * Delete an artifact or directory from storage
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact or directory
	 * @return true if deletion was successful, false if the path didn't exist
	 * @throws IOException if an I/O error occurs during deletion
	 */
	boolean delete(String repositoryId, String artifactPath) throws IOException;

	/**
	 * Stream an artifact directly to an output stream
	 * @param repositoryId the repository identifier
	 * @param artifactPath the relative path of the artifact
	 * @param outputStream the output stream to write to
	 * @throws IOException if an I/O error occurs or artifact not found
	 */
	void streamTo(String repositoryId, String artifactPath, OutputStream outputStream) throws IOException;

}