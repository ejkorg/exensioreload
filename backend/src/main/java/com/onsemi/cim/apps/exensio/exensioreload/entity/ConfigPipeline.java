package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "config_pipeline", indexes = {
    @Index(name = "idx_pipeline_site", columnList = "site"),
    @Index(name = "idx_pipeline_environment", columnList = "environment"),
    @Index(name = "idx_pipeline_sender", columnList = "sender_id"),
    @Index(name = "idx_pipeline_site_historical", columnList = "site,historical_mode_enabled")
})
public class ConfigPipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min = 1, max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String pipelineKey;

    @NotNull
    @Size(min = 1, max = 50)
    @Column(nullable = false, length = 50)
    private String site;

    @NotNull
    @Size(min = 1, max = 50)
    @Column(nullable = false, length = 50)
    private String server;

    @NotNull
    @Column(nullable = false)
    private Integer socketPort;

    @Size(max = 255)
    @Column(length = 255)
    private String configName;

    @NotNull
    @Column(name = "sender_id", nullable = false)
    private Integer senderId;

    @NotNull
    @Column(nullable = false)
    private Integer rerunPeriodMinutes;

    @NotNull
    @Size(min = 1, max = 10)
    @Column(nullable = false, length = 10)
    private String environment;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "historical_mode_enabled", nullable = false)
    private Boolean historicalModeEnabled = false;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ConfigPipelineStage> stages;

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
        if (historicalModeEnabled == null) {
            historicalModeEnabled = false;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPipelineKey() { return pipelineKey; }
    public void setPipelineKey(String pipelineKey) { this.pipelineKey = pipelineKey; }

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public String getServer() { return server; }
    public void setServer(String server) { this.server = server; }

    public Integer getSocketPort() { return socketPort; }
    public void setSocketPort(Integer socketPort) { this.socketPort = socketPort; }

    public String getConfigName() { return configName; }
    public void setConfigName(String configName) { this.configName = configName; }

    public Integer getSenderId() { return senderId; }
    public void setSenderId(Integer senderId) { this.senderId = senderId; }

    public Integer getRerunPeriodMinutes() { return rerunPeriodMinutes; }
    public void setRerunPeriodMinutes(Integer rerunPeriodMinutes) { this.rerunPeriodMinutes = rerunPeriodMinutes; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getHistoricalModeEnabled() { return historicalModeEnabled; }
    public void setHistoricalModeEnabled(Boolean historicalModeEnabled) { this.historicalModeEnabled = historicalModeEnabled; }

    public List<ConfigPipelineStage> getStages() { return stages; }
    public void setStages(List<ConfigPipelineStage> stages) { this.stages = stages; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
