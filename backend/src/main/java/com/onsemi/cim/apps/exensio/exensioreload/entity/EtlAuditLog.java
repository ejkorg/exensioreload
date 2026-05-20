package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "etl_trigger_audit_log")
public class EtlAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, length = 100)
    private String site;

    @Column(length = 100)
    private String location;

    @Column(name = "etl_server_name", nullable = false, length = 100)
    private String etlServerName;

    @Column(name = "sender_port")
    private Integer senderPort;

    @Column(nullable = false, length = 50)
    private String status;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String message;

    @Column(name = "timestamp", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    private Instant timestamp;

    @Column(name = "remote_ip", length = 45)
    private String remoteIp;

    @PrePersist
    void prePersist() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    // Constructors
    public EtlAuditLog() {}

    public EtlAuditLog(String requestId, String userId, String site, String location,
                       String etlServerName, Integer senderPort, String status,
                       String message, String remoteIp) {
        this.requestId = requestId;
        this.userId = userId;
        this.site = site;
        this.location = location;
        this.etlServerName = etlServerName;
        this.senderPort = senderPort;
        this.status = status;
        this.message = message;
        this.remoteIp = remoteIp;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getEtlServerName() { return etlServerName; }
    public void setEtlServerName(String etlServerName) { this.etlServerName = etlServerName; }

    public Integer getSenderPort() { return senderPort; }
    public void setSenderPort(Integer senderPort) { this.senderPort = senderPort; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getRemoteIp() { return remoteIp; }
    public void setRemoteIp(String remoteIp) { this.remoteIp = remoteIp; }
}
