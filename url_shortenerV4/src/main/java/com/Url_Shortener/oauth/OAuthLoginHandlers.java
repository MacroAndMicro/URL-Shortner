package com.Url_Shortener.oauth;

import com.Url_Shortener.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * What happens when Google or GitHub sends the user back.
 *
 * Success: find or create the user, then redirect the browser to the
 * frontend's /auth/callback with a one-time code, which the frontend
 * exchanges for our usual tokens. Failure (the user cancelled, or the
 * provider refused): redirect to the frontend's login page with a reason.
 *
 * The HTTP session Spring Security used to remember the sign-in in progress
 * is discarded either way: the app itself is stateless (JWT).
 */
@Component
public class OAuthLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginHandlers.class);

    private final OAuthAccountService accounts;
    private final OAuthLoginCodes codes;
    private final String frontendUrl;

    public OAuthLoginHandlers(OAuthAccountService accounts, OAuthLoginCodes codes,
                              @Value("${frontend.url:http://localhost:4200}") String frontendUrl) {
        this.accounts = accounts;
        this.codes = codes;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        org.springframework.security.core.Authentication authentication)
            throws IOException {
        endSession(request);
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String provider = token.getAuthorizedClientRegistrationId();
        try {
            User user = accounts.resolve(OAuthProfile.from(provider, token.getPrincipal()));
            String code = codes.issue(user.getId());
            response.sendRedirect(UriComponentsBuilder.fromUriString(frontendUrl)
                    .path("/auth/callback").queryParam("code", code).build().toUriString());
        } catch (RuntimeException e) {
            log.warn("Could not complete {} sign-in: {}", provider, e.getMessage());
            redirectToLogin(response, "unavailable");
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        endSession(request);
        log.info("Sign-in with provider failed: {}", exception.getMessage());
        redirectToLogin(response, "failed");
    }

    private void redirectToLogin(HttpServletResponse response, String reason) throws IOException {
        response.sendRedirect(UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/login").queryParam("oauthError", reason).build().toUriString());
    }

    private static void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
