package com.Url_Shortener.oauth;

import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The sign-in providers that are set up. A provider is enabled only when its
 * client id and secret are both configured (GOOGLE_CLIENT_ID /
 * GOOGLE_CLIENT_SECRET, GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET), so the app
 * runs normally, just without that button, until they are.
 *
 * Built here rather than with spring.security.oauth2.client properties,
 * which refuse to start with an empty client id.
 *
 * Each provider's endpoints can be overridden (app.oauth.<provider>.*-uri),
 * which the integration test uses to point Google at a fake sign-in server.
 */
@Component
public class OAuthProviders {

    private final List<ClientRegistration> registrations = new ArrayList<>();

    public OAuthProviders(Environment env) {
        add(env, OAuthProfile.GOOGLE, CommonOAuth2Provider.GOOGLE.getBuilder(OAuthProfile.GOOGLE), b -> { });
        add(env, OAuthProfile.GITHUB, CommonOAuth2Provider.GITHUB.getBuilder(OAuthProfile.GITHUB),
                // user:email lets us read which of the account's emails are verified.
                b -> b.scope("read:user", "user:email"));
    }

    private void add(Environment env, String id, ClientRegistration.Builder builder,
                     Consumer<ClientRegistration.Builder> extra) {
        String prefix = "app.oauth." + id + ".";
        String clientId = env.getProperty(prefix + "client-id", "");
        String clientSecret = env.getProperty(prefix + "client-secret", "");
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return;
        }
        builder.clientId(clientId).clientSecret(clientSecret);
        extra.accept(builder);
        override(env, prefix + "authorization-uri", builder::authorizationUri);
        override(env, prefix + "token-uri", builder::tokenUri);
        override(env, prefix + "user-info-uri", builder::userInfoUri);
        override(env, prefix + "jwk-set-uri", builder::jwkSetUri);
        override(env, prefix + "issuer-uri", builder::issuerUri);
        registrations.add(builder.build());
    }

    private static void override(Environment env, String key, Consumer<String> setter) {
        String value = env.getProperty(key, "");
        if (!value.isBlank()) {
            setter.accept(value);
        }
    }

    public List<ClientRegistration> registrations() {
        return List.copyOf(registrations);
    }

    /** Ids of the enabled providers, e.g. ["google", "github"]. */
    public List<String> enabled() {
        return registrations.stream().map(ClientRegistration::getRegistrationId).toList();
    }

    public boolean any() {
        return !registrations.isEmpty();
    }
}
