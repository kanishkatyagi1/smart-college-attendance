package com.college.attendance.config;

import com.college.attendance.security.JwtFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final Environment env;

    public SecurityConfig(Environment env) {
        this.env = env;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtFilter jwt) throws Exception {

        return http
                .csrf(c -> c.disable())
                .cors(c -> {})
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/api/auth/**", "/api/health", "/error").permitAll();
                    if (env != null && env.acceptsProfiles(Profiles.of("dev"))) {
                        a.requestMatchers("/api/dev/**").permitAll();
                    }
                    a.requestMatchers("/api/admin/**").hasRole("ADMIN")
                     .requestMatchers("/api/student/**", "/api/face/**").hasRole("STUDENT")
                     .requestMatchers("/api/teacher/**").hasRole("TEACHER")
                     .requestMatchers("/api/hod/**").hasRole("HOD")
                     .anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                )
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> response.sendError(
                HttpStatus.UNAUTHORIZED.value(),
                "Authentication is required"
        );
    }

    @Bean
AccessDeniedHandler accessDeniedHandler() {
    return (request, response, exception) -> {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"message\":\"You do not have permission to access this resource\"}"
        );
    };
}
    @Bean
FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtFilter jwt) {
    FilterRegistrationBean<JwtFilter> registration =
            new FilterRegistrationBean<>(jwt);

    registration.setEnabled(false);
    return registration;
}
}
