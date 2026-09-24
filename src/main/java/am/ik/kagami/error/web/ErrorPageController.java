package am.ik.kagami.error.web;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Renders the error page ({@code /error}) with the common site layout.
 *
 * <p>
 * Requests issued by htmx are answered with an {@code HX-Redirect} header so that the
 * browser performs a full-page navigation to the error page instead of swapping the error
 * content into a fragment target.
 */
@Controller
public class ErrorPageController implements ErrorController {

	/** Header htmx sends on every request it issues. */
	private static final String HX_REQUEST_HEADER = "HX-Request";

	@RequestMapping("/error")
	@Nullable public ModelAndView error(HttpServletRequest request, HttpServletResponse response,
			@Nullable Authentication authentication) {
		int status = resolveStatus(request, response);
		response.setStatus(status);
		if (isHtmxRequest(request)) {
			response.setHeader("HX-Redirect", "/error?status=" + status);
			return null;
		}
		Map<String, Object> model = new HashMap<>();
		model.put("title", "Error " + status);
		model.put("statusCode", status);
		model.put("statusText", statusText(status));
		// Mustache is configured strictly: every referenced key must be present even
		// when a value is optional (e.g. an access-denied forward carries no message)
		String message = errorMessage(request);
		model.put("message", message != null ? message : "The server could not process your request.");
		String path = errorPath(request);
		model.put("path", path);
		model.put("hasPath", path != null);
		model.put("userName", authentication != null ? authentication.getName() : "");
		return new ModelAndView("pages/error", model);
	}

	private int resolveStatus(HttpServletRequest request, HttpServletResponse response) {
		Object attribute = request.getAttribute("jakarta.servlet.error.status_code");
		if (attribute instanceof Integer statusCode) {
			return statusCode;
		}
		// Direct navigation (e.g. HX-Redirect target): /error?status=404
		String param = request.getParameter("status");
		if (param != null) {
			try {
				return Integer.parseInt(param);
			}
			catch (NumberFormatException ignored) {
				// fall through
			}
		}
		return response.getStatus();
	}

	private String statusText(int status) {
		HttpStatus httpStatus = HttpStatus.resolve(status);
		return httpStatus != null ? httpStatus.getReasonPhrase() : "Error";
	}

	@Nullable private String errorMessage(HttpServletRequest request) {
		Object message = request.getAttribute("jakarta.servlet.error.message");
		return message != null ? message.toString() : null;
	}

	@Nullable private String errorPath(HttpServletRequest request) {
		Object path = request.getAttribute("jakarta.servlet.error.request_uri");
		return path != null ? path.toString() : null;
	}

	/**
	 * Whether the request was issued by htmx and therefore must not render a full page.
	 */
	public static boolean isHtmxRequest(HttpServletRequest request) {
		return "true".equals(request.getHeader(HX_REQUEST_HEADER));
	}

}
