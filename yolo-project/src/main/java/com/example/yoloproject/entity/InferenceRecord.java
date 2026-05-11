package com.example.yoloproject.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inference_records")
public class InferenceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "data_name", nullable = false)
    private String dataName;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "map50")
    private Double map50;

    @Column(name = "map50_95")
    private Double map5095;

    @Column(name = "precision")
    private Double precision;

    @Column(name = "recall")
    private Double recall;

    @Column(name = "predict_dir")
    private String predictDir;

    public InferenceRecord() {
        this.createdAt = LocalDateTime.now();
        this.status = "running";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getDataName() { return dataName; }
    public void setDataName(String dataName) { this.dataName = dataName; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getMap50() { return map50; }
    public void setMap50(Double map50) { this.map50 = map50; }
    public Double getMap5095() { return map5095; }
    public void setMap5095(Double map5095) { this.map5095 = map5095; }
    public Double getPrecision() { return precision; }
    public void setPrecision(Double precision) { this.precision = precision; }
    public Double getRecall() { return recall; }
    public void setRecall(Double recall) { this.recall = recall; }
    public String getPredictDir() { return predictDir; }
    public void setPredictDir(String predictDir) { this.predictDir = predictDir; }
}