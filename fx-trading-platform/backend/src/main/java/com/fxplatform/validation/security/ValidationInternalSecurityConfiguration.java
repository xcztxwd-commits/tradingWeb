package com.fxplatform.validation.security;

import com.fxplatform.common.security.SecurityErrorResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Dedicated higher-priority security chain for validation loopback endpoints. */
@Profile("validation")
@Configuration(proxyBeanMethods = false)
public class ValidationInternalSecurityConfiguration {

  @Bean
  public ValidationInternalAuthenticationFilter validationInternalAuthenticationFilter(
      @Value("${validation.internal.secret}") String configuredSecret,
      SecurityErrorResponseWriter errorResponseWriter
  ) {
    return new ValidationInternalAuthenticationFilter(configuredSecret, errorResponseWriter);
  }

  @Bean
  public FilterRegistrationBean<ValidationInternalAuthenticationFilter>
      validationInternalAuthenticationFilterRegistration(
          ValidationInternalAuthenticationFilter authenticationFilter
      ) {
    FilterRegistrationBean<ValidationInternalAuthenticationFilter> registration =
        new FilterRegistrationBean<>(authenticationFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public FilterRegistrationBean<ValidationLoopbackRequestActivityFilter>
      validationLoopbackRequestActivityFilterRegistration(
          ValidationLoopbackRequestActivityFilter activityFilter
      ) {
    FilterRegistrationBean<ValidationLoopbackRequestActivityFilter> registration =
        new FilterRegistrationBean<>(activityFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  @Order(1)
  public SecurityFilterChain validationInternalSecurityFilterChain(
      HttpSecurity http,
      ValidationInternalAuthenticationFilter authenticationFilter,
      ValidationLoopbackRequestActivityFilter activityFilter,
      SecurityErrorResponseWriter errorResponseWriter
  ) throws Exception {
    return http
        .securityMatcher("/internal/validation/**")
        .csrf(AbstractHttpConfigurer::disable)
        .cors(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint((request, response, failure) ->
                errorResponseWriter.writeValidationInternalUnauthorized(response))
            .accessDeniedHandler((request, response, failure) ->
                errorResponseWriter.writeValidationInternalUnauthorized(response)))
        .authorizeHttpRequests(authorize -> authorize
            .anyRequest().hasAuthority(ValidationInternalAuthenticationFilter.AUTHORITY))
        .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(activityFilter, ValidationInternalAuthenticationFilter.class)
        .build();
  }
}
