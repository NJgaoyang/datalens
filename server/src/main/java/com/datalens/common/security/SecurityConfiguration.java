package com.datalens.common.security;

import com.datalens.common.web.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errors) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/system/info",
                        "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .anyRequest().denyAll());
        http.formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        // CSRF stays enabled. M01 will introduce real accounts and secure server-side sessions.
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, cause) -> errors.write(request, response,
                        401, "UNAUTHENTICATED", "请先登录；当前 M00 尚未启用账号模块"))
                .accessDeniedHandler((request, response, cause) -> errors.write(request, response,
                        403, "FORBIDDEN", "无权执行此操作")));
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(frame -> frame.deny()));
        return http.build();
    }

    @Bean
    UserDetailsService unavailableIdentityProvider() {
        // Prevent Spring Boot from generating a usable default account/password in M00.
        return username -> { throw new UsernameNotFoundException("Identity is not implemented in M00"); };
    }
}
