package com.Url_Shortener.oauth;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * What we use from a Google or GitHub account.
 *
 * @param provider          "google" or "github"
 * @param providerUserId    the provider's stable id for the account
 * @param email             email address, if the provider shared one
 * @param emailVerified     whether the provider confirmed the address belongs
 *                          to the account; only then is it used to link
 * @param suggestedUsername starting point for a new user's username
 */
public record OAuthProfile(String provider, String providerUserId, String email,
                           boolean emailVerified, String suggestedUsername) {

    static final String GOOGLE = "google";
    static final String GITHUB = "github";

    /** Builds the profile from what Spring Security loaded for the provider. */
    static OAuthProfile from(String provider, OAuth2User user) {
        return switch (provider) {
            case GOOGLE -> fromGoogle((OidcUser) user);
            case GITHUB -> fromGitHub(user);
            default -> throw new IllegalArgumentException("Unsupported sign-in provider: " + provider);
        };
    }

    private static OAuthProfile fromGoogle(OidcUser user) {
        String email = user.getEmail();
        boolean verified = Boolean.TRUE.equals(user.getEmailVerified());
        String name = email != null ? email.substring(0, email.indexOf('@')) : user.getGivenName();
        return new OAuthProfile(GOOGLE, user.getSubject(), email, verified, name);
    }

    private static OAuthProfile fromGitHub(OAuth2User user) {
        Object id = user.getAttribute("id");
        String login = user.getAttribute("login");
        // Set by GitHubOAuth2UserService from GitHub's list of verified emails.
        String verifiedEmail = user.getAttribute(GitHubOAuth2UserService.VERIFIED_EMAIL);
        return new OAuthProfile(GITHUB, String.valueOf(id), verifiedEmail, verifiedEmail != null, login);
    }
}
