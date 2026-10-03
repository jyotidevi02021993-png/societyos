package in.societyos.marketplace.platform.security.config;

import in.societyos.marketplace.platform.security.PermissionEvaluator;
import in.societyos.marketplace.platform.security.PermissionResolver;
import in.societyos.marketplace.platform.security.RevokedTokenValidator;
import in.societyos.marketplace.platform.security.SosClaims;
import in.societyos.marketplace.platform.security.SosSecurityProperties;
import in.societyos.marketplace.platform.security.TenantResolverFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import feign.RequestInterceptor;
import in.societyos.marketplace.platform.security.FieldCrypto;
import in.societyos.marketplace.platform.security.IdentityPermissionsClient;
import in.societyos.marketplace.platform.security.RemotePermissionResolver;
import in.societyos.marketplace.platform.security.TenantForwardingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Zero trust inside the cluster: every service re-validates the JWT (the gateway already did),
 * binds the tenant, and enforces {@code @perm.has(...)} method security.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SosSecurityProperties.class)
@EnableMethodSecurity
public class PlatformSecurityConfig {

  private static final String[] ALWAYS_PUBLIC = {
    "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/error"
  };

  @Bean
  SecurityFilterChain sosSecurityFilterChain(HttpSecurity http, SosSecurityProperties props) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> {
              a.requestMatchers(ALWAYS_PUBLIC).permitAll();
              a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
              if (!props.getPublicPaths().isEmpty()) {
                a.requestMatchers(props.getPublicPaths().toArray(String[]::new)).permitAll();
              }
              a.anyRequest().authenticated();
            })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .addFilterAfter(new TenantResolverFilter(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /** The tenant filter is added to the security chain only, never as a plain servlet filter. */
  @Bean
  FilterRegistrationBean<TenantResolverFilter> disableTenantResolverServletRegistration() {
    var reg = new FilterRegistrationBean<>(new TenantResolverFilter());
    reg.setEnabled(false);
    return reg;
  }

  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(PlatformSecurityConfig::authorities);
    return converter;
  }

  static Collection<GrantedAuthority> authorities(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList(SosClaims.ROLES);
    List<GrantedAuthority> result = new ArrayList<>();
    if (roles != null) {
      roles.forEach(r -> result.add(new SimpleGrantedAuthority("ROLE_" + r)));
    }
    return result;
  }

  /** Tokens are issued by identity-service; its public keys come from the JWKS endpoint. */
  @Bean
  JwtDecoder jwtDecoder(SosSecurityProperties props, StringRedisTemplate redis) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwksUri()).build();
    decoder.setJwtValidator(validator(props, redis));
    return decoder;
  }

  @Bean
  PermissionResolver remotePermissionResolver(
      IdentityPermissionsClient identity, StringRedisTemplate redis, SosSecurityProperties props) {
    return new RemotePermissionResolver(identity, redis, props.getPermissionCacheTtl());
  }

  /** AES-GCM + HMAC for PII columns. Local keys are for development only (KMS in prod). */
  @Bean
  FieldCrypto fieldCrypto(
      @Value("${sos.crypto.field-key}") String fieldKey, @Value("${sos.crypto.hash-key}") String hashKey) {
    return new FieldCrypto(fieldKey, hashKey);
  }

  /** Forwards the caller's token and society on every Feign call (services act as the user). */
  @Bean
  RequestInterceptor tenantForwardingInterceptor() {
    return new TenantForwardingInterceptor();
  }

  public static OAuth2TokenValidator<Jwt> validator(SosSecurityProperties props, StringRedisTemplate redis) {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(JwtValidators.createDefaultWithIssuer(props.getIssuer()));
    if (redis != null) {
      validators.add(new RevokedTokenValidator(redis));
    }
    return new DelegatingOAuth2TokenValidator<>(validators);
  }

  @Bean(name = "perm")
  PermissionEvaluator perm(PermissionResolver resolver) {
    return new PermissionEvaluator(resolver);
  }
}
