package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "config_etl_server", indexes = {
    @Index(name = "idx_etl_server_key", columnList = "server_key"),
    @Index(name = "idx_etl_server_environment", columnList = "environment"),
    @Index(name = "idx_etl_server_historical", columnList = "is_historical_sender")
})
public class ConfigEtlServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min = 1, max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String serverKey;

    @NotNull
    @Size(min = 1, max = 255)
    @Column(nullable = false, length = 255)
    private String host;

    @NotNull
    @Column(nullable = false)
    private Integer sshPort;

    @NotNull
    @Column(nullable = false)
    private Integer socketPort;

    @NotNull
    @Size(min = 1, max = 100)
    @Column(nullable = false, length = 100)
    private String user;

    @NotNull
    @Size(min = 1, max = 500)
    @Column(nullable = false, length = 500)
    private String encryptedPassword;

    @NotNull
    @Column(nullable = false)
    private Integer timeoutMs;

    @NotNull
    @Size(min = 1, max = 10)
    @Column(nullable = false, length = 10)
    private String environment;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "is_historical_sender", nullable = false)
    private Boolean isHistoricalSender = false;

    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    private Instant createdAt;

    @Column(name = "updated_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    private Instant updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        if (enabled == null) {
            enabled = true;
        }
        if (isHistoricalSender == null) {
            isHistoricalSender = false;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getServerKey() { return serverKey; }
    public void setServerKey(String serverKey) { this.serverKey = serverKey; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getSshPort() { return sshPort; }
    public void setSshPort(Integer sshPort) { this.sshPort = sshPort; }

    public Integer getSocketPort() { return socketPort; }
    public void setSocketPort(Integer socketPort) { this.socketPort = socketPort; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getIsHistoricalSender() { return isHistoricalSender; }
    public void setIsHistoricalSender(Boolean isHistoricalSender) { this.isHistoricalSender = isHistoricalSender; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
