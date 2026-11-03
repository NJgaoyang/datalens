package com.datalens.common.security;
import com.datalens.common.web.ApiErrorWriter;
import com.datalens.identity.SessionValidityFilter;
import com.datalens.permission.PermissionService;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
@Configuration
public class SecurityConfiguration {
    @Bean SecurityContextRepository securityContexts() {return new HttpSessionSecurityContextRepository();}
    @Bean CsrfTokenRepository csrfTokens() {return new HttpSessionCsrfTokenRepository();}
    @Bean PasswordEncoder passwords() {return new BCryptPasswordEncoder(12);}
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,ApiErrorWriter errors,PermissionService permissions,
            SecurityContextRepository contexts,CsrfTokenRepository csrf) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET,"/api/v1/system/info","/actuator/health/liveness","/actuator/health/readiness","/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/me","/api/v1/auth/logout","/api/v1/auth/password").authenticated()
                .requestMatchers("/api/v1/admin/users","/api/v1/admin/users/*/enabled","/api/v1/admin/users/*/password").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/admin/sources", "/api/v1/admin/sources/*", "/api/v1/admin/sources/*/test", "/api/v1/admin/sources/*/harvest", "/api/v1/admin/sources/*/metadata", "/api/v1/admin/subjects", "/api/v1/admin/subjects/*").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.GET,"/api/v1/subjects","/api/v1/subjects/*/metadata").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/query-tasks","/api/v1/query-tasks/*/cancel").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/query-tasks/*","/api/v1/query-tasks/*/result").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/charts","/api/v1/charts/*","/api/v1/charts/previews/*","/api/v1/charts/previews/*/result","/api/v1/charts/*/refreshes/*/result").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/charts/*/owner").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.PUT,"/api/v1/charts/*/sharing").authenticated()
                .requestMatchers(HttpMethod.DELETE,"/api/v1/charts/*").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/charts","/api/v1/charts/previews","/api/v1/charts/*/revisions","/api/v1/charts/*/refreshes","/api/v1/charts/*/restore").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/semantics/*/published","/api/v1/semantics/*/search").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/semantics/*/draft","/api/v1/semantics/*/releases").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.PUT,"/api/v1/semantics/*/draft").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.POST,"/api/v1/semantics/*/review","/api/v1/semantics/*/publish","/api/v1/semantics/*/withdraw").hasRole("SUPER_ADMIN")
                .anyRequest().denyAll());
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable);
        http.securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true));
        http.csrf(config -> config.csrfTokenRepository(csrf));
        http.addFilterAfter(new SessionValidityFilter(permissions,errors),SecurityContextHolderFilter.class);
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request,response,cause) -> errors.write(request,response,401,"UNAUTHENTICATED","请先登录"))
                .accessDeniedHandler((request,response,cause) -> errors.write(request,response,403,"FORBIDDEN","无权执行此操作或 CSRF 验证失败")));
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(frame -> frame.deny()));
        return http.build();
    }
    @Bean UserDetailsService disabledDefaultProvider() {
        return username -> {throw new UsernameNotFoundException("Use the session login endpoint");};
    }
}
