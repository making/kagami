package am.ik.kagami;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KagamiApplication {

	public static void main(String[] args) {
		SpringApplication.run(KagamiApplication.class, args);
	}

}
