package com.onsemi.cim.apps.exensio.exensioreload.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "config_pipeline_stage")
public class ConfigPipelineStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private ConfigPipeline pipeline;

    @NotNull
    @Size(min = 1, max = 50)
    @Column(nullable = false, length = 50)
    private String name;

    @NotNull
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private StageType type;

    @NotNull
    @Column(nullable = false)
    private Integer timeoutMinutes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "config_stage_dependency", joinColumns = @JoinColumn(name = "stage_id"))
    @Column(name = "depends_on", length = 50)
    private Set<String> dependsOn = new HashSet<>();

    @NotNull
    @Column(nullable = false)
    private Integer executionOrder;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ConfigPipeline getPipeline() { return pipeline; }
    public void setPipeline(ConfigPipeline pipeline) { this.pipeline = pipeline; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public StageType getType() { return type; }
    public void setType(StageType type) { this.type = type; }

    public Integer getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(Integer timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

    public Set<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(Set<String> dependsOn) { this.dependsOn = dependsOn; }

    public Integer getExecutionOrder() { return executionOrder; }
    public void setExecutionOrder(Integer executionOrder) { this.executionOrder = executionOrder; }
}
