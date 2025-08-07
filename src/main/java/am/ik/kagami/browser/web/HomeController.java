package am.ik.kagami.browser.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

	@GetMapping(path = { "/", "/browse", "/browse/**" })
	public String index() {
		return "forward:/index.html";
	}

}
