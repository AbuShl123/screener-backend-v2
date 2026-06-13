package dev.abu.screener_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Screener Backend application.
 *
 * <p>Runs as a Spring MVC (servlet/Tomcat) application. {@code spring-boot-starter-webflux}
 * is on the classpath purely for {@link org.springframework.web.reactive.function.client.WebClient}
 * support — {@code spring.main.web-application-type=servlet} in {@code application.yml} ensures
 * Tomcat is used instead of Netty.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class ScreenerBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScreenerBackendApplication.class, args);
	}
}
