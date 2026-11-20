package am.ik.kagami.registry;

/**
 * How a format handler treats the content at an artifact path with respect to the
 * upstream.
 * <p>
 * A Maven artifact is immutable: once fetched it is served from storage until it is
 * deleted. An index document (npm packument, PyPI simple index, Maven
 * {@code maven-metadata.xml}) is mutable and contains absolute upstream URLs, so it is
 * served through the metadata cache and revalidated after its TTL.
 */
public enum RevalidationPolicy {

	/** Content that never changes upstream, served from artifact storage. */
	IMMUTABLE,

	/** Content that changes upstream, served through the metadata cache. */
	MUTABLE_INDEX

}
