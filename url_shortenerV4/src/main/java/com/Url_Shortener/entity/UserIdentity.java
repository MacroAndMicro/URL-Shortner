package com.Url_Shortener.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A Google or GitHub account linked to a user, found again on later sign-ins
 * by (provider, providerUserId).
 */
@Entity
@Table(name = "user_identities",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_identities_provider_account",
                columnNames = {"provider", "provider_user_id"}))
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** "google" or "github". */
    @Column(nullable = false, length = 20)
    private String provider;

    /** The provider's stable id for the account, not the email. */
    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    /** Email the provider reported at linking time; for reference only. */
    private String email;

    @Column(nullable = false)
    private LocalDateTime createdDate;

    protected UserIdentity() {
    }

    public UserIdentity(User user, String provider, String providerUserId, String email, LocalDateTime createdDate) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.createdDate = createdDate;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getEmail() {
        return email;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }
}
