package in.societyos.gateway;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

  private static final String[] ALWAYS_PUBLIC = {
    "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/fallback/**"
  };

  @Bean
  SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, GatewaySecurityProperties props) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .cors(c -> c.configurationSource(cors(props)))
        .authorizeExchange(
            e ->
                e.pathMatchers(ALWAYS_PUBLIC).permitAll()
                    .pathMatchers(props.publicPaths().toArray(String[]::new)).permitAll()
                    .pathMatchers(HttpMethod.OPTIONS).permitAll()
                    .anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(GatewaySecurityProperties props) {
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(props.jwksUri()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
    return decoder;
  }

  private static CorsConfigurationSource cors(GatewaySecurityProperties props) {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(props.allowedOrigins());
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Society-Id", "Idempotency-Key", "If-Match", "traceparent"));
    c.setExposedHeaders(List.of("X-Trace-Id", "ETag", "Idempotent-Replayed", "X-RateLimit-Remaining"));
    c.setAllowCredentials(true);
    c.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", c);
    return source;
  }
}
