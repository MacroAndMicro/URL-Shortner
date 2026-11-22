package com.Url_Shortener.oauth;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a GitHub user and adds their primary verified email.
 *
 * GitHub's user profile shows only a public email, which the user may have
 * hidden and which GitHub does not mark as verified. Accounts are linked by
 * email only when the email is verified, so this reads the account's email
 * list (needs the user:email scope) and picks the primary verified one.
 * Other providers are loaded unchanged.
 */
@Component
public class GitHubOAuth2UserService extends DefaultOAuth2UserService {

    /** Attribute added to GitHub users: their primary verified email, if any. */
    public static final String VERIFIED_EMAIL = "verified_email";

    private static final String EMAILS_URI = "https://api.github.com/user/emails";

    private final RestClient http = RestClient.create();

    record GitHubEmail(String email, boolean primary, boolean verified) {
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        if (!OAuthProfile.GITHUB.equals(request.getClientRegistration().getRegistrationId())) {
            return user;
        }
        Map<String, Object> attributes = new HashMap<>(user.getAttributes());
        String verified = primaryVerifiedEmail(fetchEmails(request.getAccessToken().getTokenValue()));
        if (verified != null) {
            attributes.put(VERIFIED_EMAIL, verified);
        }
        return new DefaultOAuth2User(user.getAuthorities(), attributes, "id");
    }

    List<GitHubEmail> fetchEmails(String accessToken) {
        try {
            List<GitHubEmail> emails = http.get().uri(EMAILS_URI)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GitHubEmail>>() { });
            return emails != null ? emails : List.of();
        } catch (RuntimeException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("github_emails_unavailable", "Could not read GitHub emails", null), e);
        }
    }

    /** The primary email if verified; otherwise none, so nothing is linked by email. */
    static String primaryVerifiedEmail(List<GitHubEmail> emails) {
        return emails.stream()
                .filter(e -> e.primary() && e.verified())
                .map(GitHubEmail::email)
                .findFirst()
                .orElse(null);
    }
}
