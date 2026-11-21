package com.Url_Shortener.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hashes client IPs for analytics, so repeat visitors can be told apart
 * without storing addresses. Without a secret salt, IPv4 hashes can be
 * reversed by brute force, so set ANALYTICS_IP_SALT in real deployments.
 */
@Component
public class IpHasher {

    private final String salt;

    public IpHasher(@Value("${app.analytics.ip-salt:}") String salt) {
        this.salt = salt;
    }

    public String hash(String ipAddress) {
        return ipAddress != null ? Sha256.hex(salt + ipAddress) : null;
    }
}
