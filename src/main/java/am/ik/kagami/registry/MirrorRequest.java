package am.ik.kagami.registry;

import java.util.Objects;
import am.ik.kagami.KagamiProperties.Repository;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * A mirrored request routed to a format-specific {@link MirrorHandler}.
 * <p>
 * The artifact path has already been extracted from the request URI and URL-decoded, so
 * that an encoded slash in a scoped npm package ({@code @scope%2Fname}) reaches the
 * handler as {@code @scope/name}. Each handler validates it through
 * {@code ArtifactLocation} before touching storage.
 *
 * @param repositoryId the identifier of the repository the request is addressed to
 * @param repository the configuration of that repository
 * @param artifactPath the decoded path relative to the repository root
 * @param headers the incoming request headers
 * @param baseUrl the externally visible base URL of this Kagami instance, used to rewrite
 * absolute links in index documents
 */
public record MirrorRequest(String repositoryId, Repository repository, String artifactPath, HttpHeaders headers,
		String baseUrl) {

	public MirrorRequest {
		Objects.requireNonNull(repositoryId, "repositoryId is required");
		Objects.requireNonNull(repository, "repository is required");
		Objects.requireNonNull(artifactPath, "artifactPath is required");
		Objects.requireNonNull(headers, "headers is required");
		Objects.requireNonNull(baseUrl, "baseUrl is required");
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		@Nullable private String repositoryId;

		@Nullable private Repository repository;

		@Nullable private String artifactPath;

		@Nullable private HttpHeaders headers;

		@Nullable private String baseUrl;

		private Builder() {
		}

		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder repositoryId(String repositoryId) {
			this.repositoryId = repositoryId;
			return this;
		}

		public Builder repository(Repository repository) {
			this.repository = repository;
			return this;
		}

		public Builder artifactPath(String artifactPath) {
			this.artifactPath = artifactPath;
			return this;
		}

		public Builder headers(HttpHeaders headers) {
			this.headers = headers;
			return this;
		}

		public MirrorRequest build() {
			return new MirrorRequest(Objects.requireNonNull(this.repositoryId, "repositoryId is required"),
					Objects.requireNonNull(this.repository, "repository is required"),
					Objects.requireNonNull(this.artifactPath, "artifactPath is required"),
					Objects.requireNonNull(this.headers, "headers is required"),
					Objects.requireNonNull(this.baseUrl, "baseUrl is required"));
		}

	}

}
