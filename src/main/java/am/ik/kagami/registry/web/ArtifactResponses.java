package am.ik.kagami.registry.web;

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Adds the HTTP transport behavior that every mirrored artifact response shares.
 * <p>
 * Format handlers decide what an artifact is and which headers identify it; this class
 * adds resumable download metadata ({@code Accept-Ranges}, {@code ETag},
 * {@code Last-Modified}) and conditional requests on top, so that a client such as uv or
 * npm can resume a large tarball and revalidate a cached one. Range requests themselves
 * are answered by Spring MVC's resource conversion, which the advertised
 * {@code Accept-Ranges} enables. Existing headers such as {@code Content-Type},
 * {@code Content-Disposition} and {@code Cache-Control} are preserved.
 */
final class ArtifactResponses {

	private ArtifactResponses() {
	}

	/**
	 * Apply conditional and range handling to a handler response.
	 * @param response the response built by the format handler
	 * @param requestHeaders the incoming request headers
	 * @param head whether the request method is HEAD, in which case only the headers are
	 * returned without a body
	 * @return the response to send, possibly 304 or 206, or the original one for a
	 * non-success response
	 */
	static ResponseEntity<Resource> enhance(ResponseEntity<Resource> response, HttpHeaders requestHeaders,
			boolean head) {
		Resource resource = response.getBody();
		if (!response.getStatusCode().is2xxSuccessful() || resource == null) {
			return response;
		}
		long length = response.getHeaders().getContentLength();
		if (length < 0) {
			length = contentLength(resource);
		}
		long lastModified = lastModified(resource);

		HttpHeaders headers = new HttpHeaders();
		headers.putAll(response.getHeaders());
		headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
		String etag = null;
		if (length >= 0) {
			etag = "\"%x-%x\"".formatted(length, Math.max(lastModified, 0));
			headers.setETag(etag);
		}
		if (lastModified > 0) {
			headers.setLastModified(lastModified);
		}

		if (isNotModified(requestHeaders, etag, lastModified)) {
			headers.remove(HttpHeaders.CONTENT_LENGTH);
			return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
		}
		if (head) {
			// The same metadata as GET, without a body
			return new ResponseEntity<>(headers, response.getStatusCode());
		}
		// A GET with a Range header is turned into a 206 by Spring MVC's resource
		// conversion, which uses the Accept-Ranges advertised above
		return new ResponseEntity<>(resource, headers, response.getStatusCode());
	}

	private static long contentLength(Resource resource) {
		try {
			return resource.contentLength();
		}
		catch (IOException e) {
			return -1;
		}
	}

	private static long lastModified(Resource resource) {
		try {
			return resource.lastModified();
		}
		catch (IOException e) {
			return -1;
		}
	}

	private static boolean isNotModified(HttpHeaders requestHeaders, @Nullable String etag, long lastModified) {
		String ifNoneMatch = requestHeaders.getFirst(HttpHeaders.IF_NONE_MATCH);
		if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
			if (etag == null) {
				return false;
			}
			for (String candidate : ifNoneMatch.split(",")) {
				String token = candidate.trim();
				if (token.equals("*")) {
					return true;
				}
				if (token.startsWith("W/")) {
					token = token.substring(2);
				}
				if (token.equals(etag)) {
					return true;
				}
			}
			return false;
		}
		// If-Modified-Since is only honored when If-None-Match is absent
		long ifModifiedSince = requestHeaders.getIfModifiedSince();
		return ifModifiedSince >= 0 && lastModified > 0 && lastModified / 1000 <= ifModifiedSince / 1000;
	}

}
