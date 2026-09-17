package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "config_db_connection", indexes = {
    @Index(name = "idx_db_connection_key", columnList = "connection_key"),
    @Index(name = "idx_db_connection_environment", columnList = "environment")
})
public class ConfigDbConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min = 1, max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String connectionKey;

    @NotNull
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DbType dbType;

    @NotNull
    @Size(min = 1, max = 100)
    @Column(nullable = false, length = 100)
    private String schema;

    @NotNull
    @Size(min = 1, max = 255)
    @Column(nullable = false, length = 255)
    private String host;

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
    private Integer connectionTimeoutMs;

    @NotNull
    @Column(nullable = false)
    private Integer maximumPoolSize;

    @NotNull
    @Column(nullable = false)
    private Integer minimumIdle;

    @Size(max = 500)
    @Column(length = 500)
    private String downloadUrl;

    @NotNull
    @Size(min = 1, max = 10)
    @Column(nullable = false, length = 10)
    private String environment;

    @Column(nullable = false)
    private Boolean enabled = true;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConnectionKey() { return connectionKey; }
    public void setConnectionKey(String connectionKey) { this.connectionKey = connectionKey; }

    public DbType getDbType() { return dbType; }
    public void setDbType(DbType dbType) { this.dbType = dbType; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public Integer getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public void setConnectionTimeoutMs(Integer connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }

    public Integer getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(Integer maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }

    public Integer getMinimumIdle() { return minimumIdle; }
    public void setMinimumIdle(Integer minimumIdle) { this.minimumIdle = minimumIdle; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
