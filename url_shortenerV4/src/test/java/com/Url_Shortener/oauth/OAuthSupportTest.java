package com.Url_Shortener.oauth;

import com.Url_Shortener.exception.InvalidSignInCodeException;
import com.Url_Shortener.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OAuthSupportTest {

    // ============================================================
    // PROFILES
    // ============================================================

    @Test
    void googleProfileUsesTheSubjectAndVerifiedFlag() {
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "1234", "email", "ana.silva@example.com", "email_verified", true));
        var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);

        OAuthProfile profile = OAuthProfile.from("google", user);

        assertThat(profile).isEqualTo(new OAuthProfile("google", "1234", "ana.silva@example.com", true, "ana.silva"));
    }

    @Test
    void gitHubProfileOnlyHasAnEmailWhenGitHubVerifiedIt() {
        var withEmail = new DefaultOAuth2User(List.of(), Map.of("id", 42, "login", "octocat",
                GitHubOAuth2UserService.VERIFIED_EMAIL, "octo@example.com"), "id");
        var withoutEmail = new DefaultOAuth2User(List.of(), Map.of("id", 43, "login", "shy",
                "email", "public-but-unverified@example.com"), "id");

        assertThat(OAuthProfile.from("github", withEmail))
                .isEqualTo(new OAuthProfile("github", "42", "octo@example.com", true, "octocat"));
        assertThat(OAuthProfile.from("github", withoutEmail))
                .isEqualTo(new OAuthProfile("github", "43", null, false, "shy"));
    }

    @Test
    void onlyThePrimaryVerifiedGitHubEmailCounts() {
        var emails = List.of(
                new GitHubOAuth2UserService.GitHubEmail("old@example.com", false, true),
                new GitHubOAuth2UserService.GitHubEmail("main@example.com", true, true));
        var unverifiedPrimary = List.of(
                new GitHubOAuth2UserService.GitHubEmail("main@example.com", true, false),
                new GitHubOAuth2UserService.GitHubEmail("other@example.com", false, true));

        assertThat(GitHubOAuth2UserService.primaryVerifiedEmail(emails)).isEqualTo("main@example.com");
        assertThat(GitHubOAuth2UserService.primaryVerifiedEmail(unverifiedPrimary)).isNull();
    }

    // ============================================================
    // PROVIDERS
    // ============================================================

    @Test
    void providersAreOffUntilBothIdAndSecretAreSet() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.oauth.google.client-id", "id-only")
                .withProperty("app.oauth.github.client-id", "gh-id")
                .withProperty("app.oauth.github.client-secret", "gh-secret");

        OAuthProviders providers = new OAuthProviders(env);

        assertThat(providers.enabled()).containsExactly("github");
        assertThat(providers.registrations().get(0).getScopes()).contains("user:email");
        assertThat(new OAuthProviders(new MockEnvironment()).any()).isFalse();
    }

    @Test
    void providerEndpointsCanBeOverridden() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.oauth.google.client-id", "id")
                .withProperty("app.oauth.google.client-secret", "secret")
                .withProperty("app.oauth.google.token-uri", "http://localhost:1234/token");

        var google = new OAuthProviders(env).registrations().get(0);

        assertThat(google.getProviderDetails().getTokenUri()).isEqualTo("http://localhost:1234/token");
        assertThat(google.getProviderDetails().getAuthorizationUri()).startsWith("https://accounts.google.com/");
    }

    // ============================================================
    // ONE-TIME CODES
    // ============================================================

    @Test
    @SuppressWarnings("unchecked")
    void aCodeIsStoredHashedAndWorksOnce() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        OAuthLoginCodes codes = new OAuthLoginCodes(redis, CircuitBreakerRegistry.ofDefaults());

        String code = codes.issue(7L);

        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), eq("7"), eq(Duration.ofSeconds(60)));
        assertThat(key.getValue()).startsWith("oauth:code:").doesNotContain(code);

        when(values.getAndDelete(key.getValue())).thenReturn("7", (String) null);
        assertThat(codes.consume(code)).isEqualTo(7L);
        assertThatThrownBy(() -> codes.consume(code)).isInstanceOf(InvalidSignInCodeException.class);
        assertThatThrownBy(() -> codes.consume("")).isInstanceOf(InvalidSignInCodeException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void signInIsUnavailableWhileRedisIsDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new IllegalStateException("redis down")).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> new OAuthLoginCodes(redis, CircuitBreakerRegistry.ofDefaults()).issue(7L))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
