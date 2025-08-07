package am.ik.kagami;

import org.springframework.boot.SpringApplication;

public class TestKagamiApplication {

	public static void main(String[] args) {
		SpringApplication.from(KagamiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
