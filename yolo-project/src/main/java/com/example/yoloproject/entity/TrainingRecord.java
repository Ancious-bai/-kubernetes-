package com.example.yoloproject.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "training_records")
public class TrainingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_name", nullable = false)
    private String dataName;

    @Column(nullable = false)
    private Integer epochs;

    @Column(nullable = false)
    private Integer imgsz;

    @Column(name = "record_name", nullable = false, unique = true)
    private String recordName;

    @Column(name = "train_job_id")
    private String trainJobId;

    @Column(name = "train_pod_name")
    private String trainPodName;

    @Column(name = "train_status")
    private String trainStatus = "IDLE";

    @Column(name = "train_progress")
    private Integer trainProgress = 0;

    @Column(name = "test_job_id")
    private String testJobId;

    @Column(name = "test_pod_name")
    private String testPodName;

    @Column(name = "test_status")
    private String testStatus = "IDLE";

    @Column(name = "created_by")
    private String createdBy;

    @Column(nullable = false)
    private Integer priority = 5;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 训练日志文件名（位于项目 logs 目录下，如 data1-e2-i640-train.txt） */
    @Column(name = "train_log_file", length = 512)
    private String trainLogFile;

    /** 测试日志文件名（位于项目 logs 目录下） */
    @Column(name = "test_log_file", length = 512)
    private String testLogFile;

    @Column(name = "target_node")
    private String targetNode;

    public TrainingRecord() {
    }

    public TrainingRecord(String dataName, int epochs, int imgsz, String createdBy) {
        this.dataName = dataName;
        this.epochs = epochs;
        this.imgsz = imgsz;
        this.recordName = dataName + "-e" + epochs + "-i" + imgsz;
        this.createdBy = createdBy;
        this.priority = 5;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDataName() { return dataName; }
    public void setDataName(String dataName) { this.dataName = dataName; }
    public Integer getEpochs() { return epochs; }
    public void setEpochs(Integer epochs) { this.epochs = epochs; }
    public Integer getImgsz() { return imgsz; }
    public void setImgsz(Integer imgsz) { this.imgsz = imgsz; }
    public String getRecordName() { return recordName; }
    public void setRecordName(String recordName) { this.recordName = recordName; }
    public String getTrainJobId() { return trainJobId; }
    public void setTrainJobId(String trainJobId) { this.trainJobId = trainJobId; }
    public String getTrainPodName() { return trainPodName; }
    public void setTrainPodName(String trainPodName) { this.trainPodName = trainPodName; }
    public String getTrainStatus() { return trainStatus; }
    public void setTrainStatus(String trainStatus) { this.trainStatus = trainStatus; }
    public Integer getTrainProgress() { return trainProgress; }
    public void setTrainProgress(Integer trainProgress) { this.trainProgress = trainProgress; }
    public String getTestJobId() { return testJobId; }
    public void setTestJobId(String testJobId) { this.testJobId = testJobId; }
    public String getTestPodName() { return testPodName; }
    public void setTestPodName(String testPodName) { this.testPodName = testPodName; }
    public String getTestStatus() { return testStatus; }
    public void setTestStatus(String testStatus) { this.testStatus = testStatus; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getTrainLogFile() { return trainLogFile; }
    public void setTrainLogFile(String trainLogFile) { this.trainLogFile = trainLogFile; }
    public String getTestLogFile() { return testLogFile; }
    public void setTestLogFile(String testLogFile) { this.testLogFile = testLogFile; }
    public String getTargetNode() { return targetNode; }
    public void setTargetNode(String targetNode) { this.targetNode = targetNode; }
}
