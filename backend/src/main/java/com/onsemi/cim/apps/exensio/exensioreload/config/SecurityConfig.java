package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;

@Configuration
public class SecurityConfig {

    private final SsoProperties ssoProperties;
    private final SsoAuthenticationSuccessHandler ssoSuccessHandler;
    private final SsoAuthenticationFailureHandler ssoFailureHandler;

    public SecurityConfig(SsoProperties ssoProperties,
                          SsoAuthenticationSuccessHandler ssoSuccessHandler,
                          SsoAuthenticationFailureHandler ssoFailureHandler) {
        this.ssoProperties = ssoProperties;
        this.ssoSuccessHandler = ssoSuccessHandler;
        this.ssoFailureHandler = ssoFailureHandler;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtUtil jwtUtil,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        // For API endpoints we want JSON 401 responses rather than redirects to HTML login pages.
        RequestMatcher apiMatcher = new AntPathRequestMatcher("/api/**");

        LinkedHashMap<RequestMatcher, org.springframework.security.web.AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(apiMatcher, new RestAuthenticationEntryPoint());

        DelegatingAuthenticationEntryPoint delegatingEntryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        // fallback to the standard login page for non-API requests
        delegatingEntryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"));

        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // SSO OAuth2 callback and initiation endpoints are always public
                        .requestMatchers(
                                "/login/oauth2/**",
                                "/oauth2/**",
                        "/api/auth/**",
                                "/sso-callback"
                        ).permitAll()
                    .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(delegatingEntryPoint))
                .formLogin(Customizer.withDefaults());

        // Conditionally enable OAuth2 / OIDC login when SSO is configured (Requirement 6.1, 6.2)
        if (ssoProperties.isEnabled()) {
            http.oauth2Login(oauth2 -> {
                oauth2.successHandler(ssoSuccessHandler);
                oauth2.failureHandler(ssoFailureHandler);
                // Wire the custom resolver so /silent can append prompt=none (Requirements 8.1, 8.4)
                if (clientRegistrationRepository != null) {
                    oauth2.authorizationEndpoint(ep -> ep
                            .authorizationRequestResolver(
                                    new PromptNoneAuthorizationRequestResolver(clientRegistrationRepository)));
                }
            });
        }

        // Add JWT authentication filter so Bearer tokens are processed for API requests.
        // This is unchanged from the existing local-login path (Requirement 6.2).
        http.addFilterBefore(new JwtAuthenticationFilter(jwtUtil), BasicAuthenticationFilter.class);

        return http.build();
    }
}
