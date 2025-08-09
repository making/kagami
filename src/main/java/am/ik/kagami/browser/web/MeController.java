package am.ik.kagami.browser.web;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

	@GetMapping(path = "/me")
	public Map<String, Object> me(Authentication authentication, CsrfToken csrfToken) {
		return Map.of("name", authentication.getName(), "crsfToken", csrfToken.getToken());
	}

}
