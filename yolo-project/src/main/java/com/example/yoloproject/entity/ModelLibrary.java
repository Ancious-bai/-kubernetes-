package com.example.yoloproject.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "model_library")
public class ModelLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "data_name", nullable = false)
    private String dataName;

    @Column(name = "record_name")
    private String recordName;

    @Column(name = "model_type", nullable = false, length = 10)
    private String modelType;

    @Column(name = "model_path", nullable = false)
    private String modelPath;

    @Column(name = "epochs")
    private Integer epochs;

    @Column(name = "imgsz")
    private Integer imgsz;

    @Column(name = "map50")
    private Double map50;

    @Column(name = "map50_95")
    private Double map5095;

    @Column(name = "precision")
    private Double precision;

    @Column(name = "recall")
    private Double recall;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ModelLibrary() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getDataName() { return dataName; }
    public void setDataName(String dataName) { this.dataName = dataName; }
    public String getRecordName() { return recordName; }
    public void setRecordName(String recordName) { this.recordName = recordName; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public String getModelPath() { return modelPath; }
    public void setModelPath(String modelPath) { this.modelPath = modelPath; }
    public Integer getEpochs() { return epochs; }
    public void setEpochs(Integer epochs) { this.epochs = epochs; }
    public Integer getImgsz() { return imgsz; }
    public void setImgsz(Integer imgsz) { this.imgsz = imgsz; }
    public Double getMap50() { return map50; }
    public void setMap50(Double map50) { this.map50 = map50; }
    public Double getMap5095() { return map5095; }
    public void setMap5095(Double map5095) { this.map5095 = map5095; }
    public Double getPrecision() { return precision; }
    public void setPrecision(Double precision) { this.precision = precision; }
    public Double getRecall() { return recall; }
    public void setRecall(Double recall) { this.recall = recall; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
