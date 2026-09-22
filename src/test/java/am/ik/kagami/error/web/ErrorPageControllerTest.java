package am.ik.kagami.error.web;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tests for {@link ErrorPageController}.
 */
class ErrorPageControllerTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ErrorPageController()).build();

	@Test
	void rendersErrorPageWithErrorAttributes() throws Exception {
		this.mockMvc
			.perform(get("/error").requestAttr("jakarta.servlet.error.status_code", 404)
				.requestAttr("jakarta.servlet.error.message", "No such repository")
				.requestAttr("jakarta.servlet.error.request_uri", "/browse/nope"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("pages/error"))
			.andExpect(model().attribute("statusCode", 404))
			.andExpect(model().attribute("statusText", "Not Found"))
			.andExpect(model().attribute("message", "No such repository"))
			.andExpect(model().attribute("path", "/browse/nope"));
	}

	@Test
	void htmxRequestGetsRedirectToErrorPage() throws Exception {
		this.mockMvc
			.perform(get("/error").header("HX-Request", "true")
				.requestAttr("jakarta.servlet.error.status_code", 500)
				.requestAttr("jakarta.servlet.error.message", "Boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string("HX-Redirect", "/error?status=500"))
			.andExpect(content().string(""));
	}

	@Test
	void directNavigationWithStatusParamRendersErrorPage() throws Exception {
		this.mockMvc.perform(get("/error").queryParam("status", "403"))
			.andExpect(status().isForbidden())
			.andExpect(view().name("pages/error"))
			.andExpect(model().attribute("statusCode", 403))
			.andExpect(model().attribute("statusText", "Forbidden"));
	}

	@Test
	void unknownStatusFallsBackToResponseStatus() throws Exception {
		this.mockMvc.perform(get("/error").queryParam("status", "not-a-number"))
			.andExpect(status().isOk())
			.andExpect(view().name("pages/error"))
			.andExpect(model().attribute("statusCode", 200));
	}

}
