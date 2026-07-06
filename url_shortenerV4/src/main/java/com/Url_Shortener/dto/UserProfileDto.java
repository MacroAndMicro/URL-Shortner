package com.Url_Shortener.dto;

import java.time.LocalDateTime;
import java.util.Set;

public class UserProfileDto {
    private Long id;
    private String username;
    private String email;
    private Set<String> roles;
    private LocalDateTime createdDate;

    public UserProfileDto() {
    }

    public UserProfileDto(Long id, String username, String email, Set<String> roles, LocalDateTime createdDate) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.roles = roles;
        this.createdDate = createdDate;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }
}
