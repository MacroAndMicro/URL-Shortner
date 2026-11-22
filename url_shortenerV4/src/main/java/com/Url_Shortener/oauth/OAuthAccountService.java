package com.Url_Shortener.oauth;

import com.Url_Shortener.entity.User;
import com.Url_Shortener.entity.UserIdentity;
import com.Url_Shortener.repository.UserIdentityRepository;
import com.Url_Shortener.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds or creates the user for a Google or GitHub sign-in:
 *
 * <ol>
 *   <li>An account already linked: its user.</li>
 *   <li>Otherwise, if the provider verified the email and a user has that
 *       email: link to that user. Unverified emails are never used, or anyone
 *       could claim an account by putting its email on a provider account.</li>
 *   <li>Otherwise: a new user on the Free plan, with a generated username and
 *       no usable password (they sign in with the provider).</li>
 * </ol>
 */
@Service
public class OAuthAccountService {

    private static final Logger log = LoggerFactory.getLogger(OAuthAccountService.class);

    static final int USERNAME_MIN = 5;
    static final int USERNAME_MAX = 50;

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthAccountService(UserRepository users, UserIdentityRepository identities,
                               PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public User resolve(OAuthProfile profile) {
        Optional<UserIdentity> linked =
                identities.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId());
        if (linked.isPresent()) {
            return linked.get().getUser();
        }

        if (profile.emailVerified() && profile.email() != null) {
            Optional<User> sameEmail = users.findByEmailIgnoreCase(profile.email());
            if (sameEmail.isPresent()) {
                link(sameEmail.get(), profile);
                log.info("Linked {} account to existing user {} by verified email", profile.provider(),
                        sameEmail.get().getId());
                return sameEmail.get();
            }
        }

        User created = users.save(newUser(profile));
        link(created, profile);
        log.info("Created user {} from {} sign-in", created.getId(), profile.provider());
        return created;
    }

    private void link(User user, OAuthProfile profile) {
        identities.save(new UserIdentity(user, profile.provider(), profile.providerUserId(),
                profile.email(), LocalDateTime.now(clock)));
    }

    private User newUser(OAuthProfile profile) {
        User user = new User();
        user.setUsername(uniqueUsername(profile.suggestedUsername()));
        user.setEmail(emailFor(profile));
        // Random and never shown: these users sign in with the provider.
        user.setPassword(passwordEncoder.encode(randomToken()));
        user.setRoles(Set.of("ROLE_USER"));
        return user;
    }

    /**
     * The provider's email when it is verified and free; otherwise a
     * placeholder, because email is required and unique. An unverified email
     * is not stored, so it can never be used to link later.
     */
    private String emailFor(OAuthProfile profile) {
        if (profile.emailVerified() && profile.email() != null && !users.existsByEmail(profile.email())) {
            return profile.email();
        }
        return profile.provider() + "-" + profile.providerUserId() + "@users.noreply.shortlink.invalid";
    }

    /** Letters, digits, '_' and '-', 5 to 50 long, not yet taken. */
    String uniqueUsername(String suggestion) {
        String base = suggestion == null ? "" : suggestion.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (base.length() < USERNAME_MIN) {
            base = base + "-user";                  // "" becomes "-user", "ab" becomes "ab-user"
        }
        base = base.substring(0, Math.min(base.length(), USERNAME_MAX - 8)); // room for a suffix
        String candidate = base;
        for (int n = 2; users.existsByUsername(candidate); n++) {
            candidate = base + n;
            if (n > 50) {
                candidate = base + "-" + Integer.toHexString(random.nextInt(0x100000));
            }
        }
        return candidate;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
