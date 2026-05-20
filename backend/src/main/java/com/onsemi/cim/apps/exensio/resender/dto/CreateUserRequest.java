package com.onsemi.cim.apps.exensio.resender.dto;

import com.onsemi.cim.apps.exensio.resender.entity.AppUser;

import java.util.Set;

public class CreateUserRequest {
    
    private String username;
    private String email;
    private String password;
    private Set<String> roles;
    private boolean enabled = true;
    private AppUser.UserStatus status = AppUser.UserStatus.ACTIVE;

    // Constructors
    public CreateUserRequest() {}

    public CreateUserRequest(String username, String email, String password, Set<String> roles) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.roles = roles;
    }

    // Getters and Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public AppUser.UserStatus getStatus() { return status; }
    public void setStatus(AppUser.UserStatus status) { this.status = status; }
}
