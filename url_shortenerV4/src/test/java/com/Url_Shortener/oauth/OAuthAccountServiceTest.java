package com.Url_Shortener.oauth;

import com.Url_Shortener.entity.User;
import com.Url_Shortener.entity.UserIdentity;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.repository.UserIdentityRepository;
import com.Url_Shortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OAuthAccountServiceTest {

    private UserRepository users;
    private UserIdentityRepository identities;
    private OAuthAccountService service;
    private final Set<String> takenUsernames = new HashSet<>();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        identities = mock(UserIdentityRepository.class);
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(99L);
            return u;
        });
        when(users.existsByUsername(anyString())).thenAnswer(inv -> takenUsernames.contains(inv.<String>getArgument(0)));
        when(identities.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        service = new OAuthAccountService(users, identities, new BCryptPasswordEncoder(4), Clock.systemUTC());
    }

    private User existing(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername("existing");
        u.setEmail(email);
        return u;
    }

    @Test
    void aLinkedAccountSignsInAsItsUser() {
        User linked = existing(5L, "a@example.com");
        when(identities.findByProviderAndProviderUserId("google", "sub-1"))
                .thenReturn(Optional.of(new UserIdentity(linked, "google", "sub-1", null, null)));

        User result = service.resolve(new OAuthProfile("google", "sub-1", "a@example.com", true, "a"));

        assertThat(result).isSameAs(linked);
        verify(users, never()).save(any());
        verify(identities, never()).save(any());
    }

    @Test
    void aVerifiedEmailLinksToTheExistingAccount() {
        User owner = existing(5L, "Ana@Example.com");
        when(users.findByEmailIgnoreCase("ana@example.com")).thenReturn(Optional.of(owner));

        User result = service.resolve(new OAuthProfile("google", "sub-1", "ana@example.com", true, "ana"));

        assertThat(result).isSameAs(owner);
        ArgumentCaptor<UserIdentity> link = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identities).save(link.capture());
        assertThat(link.getValue().getUser()).isSameAs(owner);
        assertThat(link.getValue().getProviderUserId()).isEqualTo("sub-1");
        verify(users, never()).save(any());
    }

    @Test
    void anUnverifiedEmailNeverLinksAndIsNotStored() {
        when(users.findByEmailIgnoreCase("victim@example.com")).thenReturn(Optional.of(existing(5L, "victim@example.com")));
        when(users.existsByEmail("victim@example.com")).thenReturn(true);

        User result = service.resolve(new OAuthProfile("github", "42", "victim@example.com", false, "attacker"));

        assertThat(result.getId()).isEqualTo(99L);
        assertThat(result.getEmail()).isEqualTo("github-42@users.noreply.shortlink.invalid");
        verify(users, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void aNewUserIsCreatedOnTheFreePlanWithAnUnusablePassword() {
        User result = service.resolve(new OAuthProfile("google", "sub-9", "new.person@example.com", true, "new.person"));

        assertThat(result.getUsername()).isEqualTo("newperson");
        assertThat(result.getEmail()).isEqualTo("new.person@example.com");
        assertThat(result.getPlan()).isEqualTo(Plan.FREE);
        assertThat(result.getRoles()).containsExactly("ROLE_USER");
        assertThat(result.getPassword()).startsWith("$2"); // a bcrypt hash of a random secret
        verify(identities).save(any());
    }

    @Test
    void usernamesAreValidAndUnique() {
        takenUsernames.add("octocat");
        takenUsernames.add("octocat2");

        assertThat(service.uniqueUsername("octocat")).isEqualTo("octocat3");
        assertThat(service.uniqueUsername("Al")).isEqualTo("al-user");
        assertThat(service.uniqueUsername(null)).isEqualTo("-user");
        assertThat(service.uniqueUsername("Ünïcode Name!")).isEqualTo("ncodename");
        assertThat(service.uniqueUsername("x".repeat(80))).hasSizeLessThanOrEqualTo(OAuthAccountService.USERNAME_MAX);
    }
}
