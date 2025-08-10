package am.ik.kagami.browser.web;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.AuthenticationType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

	private final OAuth2ClientProperties oAuth2ClientProperties;

	private final AuthenticationType authenticationType;

	public LoginController(@Autowired(required = false) OAuth2ClientProperties oAuth2ClientProperties,
			KagamiProperties kagamiProperties) {
		this.oAuth2ClientProperties = oAuth2ClientProperties;
		this.authenticationType = kagamiProperties.authentication().type();
	}

	@GetMapping(path = "/login")
	public String login(Model model) {
		List<Map<String, String>> oidcClients;
		if (this.authenticationType == AuthenticationType.OIDC) {
			if (oAuth2ClientProperties != null) {
				oidcClients = oAuth2ClientProperties.getRegistration()
					.entrySet()
					.stream()
					.map(entry -> Map.of("provider", entry.getKey(), "clientName",
							Objects.requireNonNullElseGet(entry.getValue().getClientName(),
									() -> toClientName(entry.getKey()))))
					.toList();
			}
			else {
				oidcClients = List.of();
			}
		}
		else {
			oidcClients = List.of();
		}
		model.addAttribute("oidcClients", oidcClients);
		return "login";
	}

	@GetMapping(path = "/logout")
	public String logout() {
		return "logout";
	}

	static String toClientName(String provider) {
		return Arrays.stream(provider.split("-")).map(StringUtils::capitalize).collect(Collectors.joining(" "));
	}

}