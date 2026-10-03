package in.societyos.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Serves {@code config-repo/<service>[-<profile>].yml}. Secrets are never stored here: they come
 * from environment variables (External Secrets / AWS Secrets Manager in Kubernetes).
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ConfigServerApplication.class, args);
  }
}
