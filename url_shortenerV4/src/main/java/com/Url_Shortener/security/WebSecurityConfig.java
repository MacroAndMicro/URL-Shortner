package com.Url_Shortener.security;

import com.Url_Shortener.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import com.Url_Shortener.oauth.GitHubOAuth2UserService;
import com.Url_Shortener.oauth.OAuthLoginHandlers;
import com.Url_Shortener.oauth.OAuthProviders;
import com.Url_Shortener.shortcode.ShortCode;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {
    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public WebSecurityConfig(UserDetailsService userDetailsService, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.userDetailsService = userDetailsService;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    // The OAuth beans are method parameters, not constructor ones: they need the
    // PasswordEncoder defined in this class, which would otherwise be a cycle.
    public SecurityFilterChain filterChain(HttpSecurity http, OAuthProviders oauthProviders,
                                           OAuthLoginHandlers oauthHandlers,
                                           GitHubOAuth2UserService gitHubUserService) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                // Applied here as well so 401 responses carry CORS headers the frontend can read.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Lets errors such as 404 and 405 reach the client instead of becoming 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/public/**").permitAll()
                        // Google/GitHub sign-in: start (/oauth2/authorization/{provider}) and return.
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/guest/shorten").permitAll()
                        // Actuator is served on management.server.port, which is not published.
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .requestMatchers(HttpMethod.GET, ShortCode.PATH_PATTERN).permitAll()
                        .anyRequest().authenticated()
                );
        if (oauthProviders.any()) {
            // Spring Security keeps the sign-in in progress in a short HTTP session
            // (the app is otherwise stateless); OAuthLoginHandlers ends it.
            http.oauth2Login(oauth -> oauth
                    .clientRegistrationRepository(
                            new InMemoryClientRegistrationRepository(oauthProviders.registrations()))
                    .userInfoEndpoint(userInfo -> userInfo.userService(gitHubUserService))
                    .successHandler(oauthHandlers)
                    .failureHandler(oauthHandlers));
        }
        http.authenticationProvider(authenticationProvider());
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
