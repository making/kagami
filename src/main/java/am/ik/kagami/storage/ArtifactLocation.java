package am.ik.kagami.storage;

/**
 * The location of a single artifact within a mirrored repository.
 *
 * @param repositoryId the repository identifier
 * @param artifactPath the path of the artifact relative to the repository root
 */
public record ArtifactLocation(String repositoryId, String artifactPath) {
}
