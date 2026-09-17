package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    private Instant createdAt;

    @Column(length = 20)
    private String status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Constructors
    public AuditLog() {}

    public AuditLog(Long userId, String action, String resourceType, String resourceId, 
                   String details, String ipAddress, String userAgent) {
        this.userId = userId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    // Audit action constants
    public static final class Actions {
        // Existing actions
        public static final String USER_CREATED = "USER_CREATED";
        public static final String USER_UPDATED = "USER_UPDATED";
        public static final String USER_DELETED = "USER_DELETED";
        public static final String USER_LOGIN = "USER_LOGIN";
        public static final String USER_LOGOUT = "USER_LOGOUT";
        public static final String ROLE_CHANGED = "ROLE_CHANGED";
        public static final String DATE_RANGE_OVERRIDE = "DATE_RANGE_OVERRIDE";
        public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
        public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
        public static final String ACCOUNT_UNLOCKED = "ACCOUNT_UNLOCKED";

        // Configuration management actions
        public static final String PIPELINE_CREATED = "PIPELINE_CREATED";
        public static final String PIPELINE_UPDATED = "PIPELINE_UPDATED";
        public static final String PIPELINE_DELETED = "PIPELINE_DELETED";
        public static final String ETL_SERVER_CREATED = "ETL_SERVER_CREATED";
        public static final String ETL_SERVER_UPDATED = "ETL_SERVER_UPDATED";
        public static final String ETL_SERVER_DELETED = "ETL_SERVER_DELETED";
        public static final String DB_CONNECTION_CREATED = "DB_CONNECTION_CREATED";
        public static final String DB_CONNECTION_UPDATED = "DB_CONNECTION_UPDATED";
        public static final String DB_CONNECTION_DELETED = "DB_CONNECTION_DELETED";

        // Session and ETL events
        public static final String SESSION_CREATED = "SESSION_CREATED";
        public static final String SESSION_COMPLETED = "SESSION_COMPLETED";
        public static final String SESSION_FAILED = "SESSION_FAILED";
        public static final String ETL_TRIGGERED = "ETL_TRIGGERED";
        public static final String CONFIG_MIGRATED = "CONFIG_MIGRATED";
    }

    // Resource type constants
    public static final class ResourceTypes {
        public static final String USER = "USER";
        public static final String PIPELINE = "PIPELINE";
        public static final String ETL_SERVER = "ETL_SERVER";
        public static final String DB_CONNECTION = "DB_CONNECTION";
        public static final String RESENDER = "RESENDER";
        public static final String SESSION = "SESSION";
        public static final String SYSTEM = "SYSTEM";
    }
}
