package am.ik.kagami;

import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.health.autoconfigure.application.DiskSpaceHealthContributorAutoConfiguration;

@SpringBootApplication(exclude = { S3AutoConfiguration.class, DiskSpaceHealthContributorAutoConfiguration.class })
@ConfigurationPropertiesScan
public class KagamiApplication {

	public static void main(String[] args) {
		SpringApplication.run(KagamiApplication.class, args);
	}

}
