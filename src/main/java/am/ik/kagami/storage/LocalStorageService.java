package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Local file system implementation of StorageService
 */
@Service
public class LocalStorageService implements StorageService {

	private final Path basePath;

	public LocalStorageService(KagamiProperties properties) {
		this.basePath = Path.of(properties.storage().path()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.basePath);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to create storage directory: " + this.basePath, e);
		}
	}

	@Override
	public void store(String repositoryId, String artifactPath, InputStream inputStream) throws IOException {
		validatePath(artifactPath);
		Path targetPath = resolvePath(repositoryId, artifactPath);
		Files.createDirectories(targetPath.getParent());
		Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public Resource retrieve(String repositoryId, String artifactPath) {
		validatePath(artifactPath);
		Path targetPath = resolvePath(repositoryId, artifactPath);
		if (Files.exists(targetPath) && Files.isRegularFile(targetPath)) {
			return new PathResource(targetPath);
		}
		return null;
	}

	@Override
	public boolean delete(String repositoryId, String artifactPath) throws IOException {
		validatePath(artifactPath);
		Path targetPath = resolvePath(repositoryId, artifactPath);

		if (!Files.exists(targetPath)) {
			return false;
		}

		if (Files.isDirectory(targetPath)) {
			// Delete directory recursively
			try (Stream<Path> walk = Files.walk(targetPath)) {
				walk.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.delete(path);
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to delete: " + path, e);
					}
				});
			}
		}
		else {
			// Delete single file
			Files.delete(targetPath);
		}

		return true;
	}

	private Path resolvePath(String repositoryId, String artifactPath) {
		// Resolve and normalize to prevent path traversal
		Path resolved = this.basePath.resolve(repositoryId).resolve(artifactPath).normalize();

		// Ensure the resolved path is within the base path
		if (!resolved.startsWith(this.basePath)) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}

		return resolved;
	}

	private void validatePath(String artifactPath) {
		if (!StringUtils.hasText(artifactPath)) {
			throw new IllegalArgumentException("Artifact path cannot be null or empty");
		}

		// Check for path traversal attempts
		if (artifactPath.contains("..") || artifactPath.contains("~")) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}
	}

}