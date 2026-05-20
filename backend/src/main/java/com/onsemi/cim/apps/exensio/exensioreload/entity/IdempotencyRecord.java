package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "etl_trigger_idempotency")
public class IdempotencyRecord {

    @Id
    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(nullable = false, length = 50)
    private String status;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String message;

    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    private java.time.Instant createdAt;

    public IdempotencyRecord() {}

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
}
