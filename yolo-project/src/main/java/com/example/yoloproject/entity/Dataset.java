package com.example.yoloproject.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "datasets")
public class Dataset {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "name", nullable = false, unique = true)
    private String name;
    
    @Column(name = "input_path", nullable = false)
    private String inputPath;
    
    @Column(name = "preprocessed")
    private Boolean preprocessed = false;
    
    @Column(name = "trained")
    private Boolean trained = false;
    
    @Column(name = "tested")
    private Boolean tested = false;
    
    @Column(name = "train_pod_name")
    private String trainPodName;
    
    @Column(name = "test_pod_name")
    private String testPodName;
    
    @Column(name = "priority")
    private Integer priority = 5; // 默认优先级为5（1-10，数字越小优先级越高）
    
    @Column(name = "train_progress")
    private Integer trainProgress = 0;
    
    @Column(name = "train_status")
    private String trainStatus = "IDLE"; // IDLE, QUEUED, RUNNING, PAUSED, COMPLETED, FAILED
    
    @Column(name = "train_job_id")
    private String trainJobId;
    
    @Column(name = "test_status")
    private String testStatus = "IDLE";
    
    @Column(name = "test_job_id")
    private String testJobId;
    
    public Dataset() {
    }
    
    public Dataset(String name, String inputPath) {
        this.name = name;
        this.inputPath = inputPath;
        this.preprocessed = false;
        this.trained = false;
        this.tested = false;
        this.priority = 5;
        this.trainProgress = 0;
        this.trainStatus = "IDLE";
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getInputPath() {
        return inputPath;
    }
    
    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }
    
    public Boolean getPreprocessed() {
        return preprocessed;
    }
    
    public void setPreprocessed(Boolean preprocessed) {
        this.preprocessed = preprocessed;
    }
    
    public Boolean getTrained() {
        return trained;
    }
    
    public void setTrained(Boolean trained) {
        this.trained = trained;
    }
    
    public Boolean getTested() {
        return tested;
    }
    
    public void setTested(Boolean tested) {
        this.tested = tested;
    }
    
    public String getTrainPodName() {
        return trainPodName;
    }
    
    public void setTrainPodName(String trainPodName) {
        this.trainPodName = trainPodName;
    }
    
    public String getTestPodName() {
        return testPodName;
    }
    
    public void setTestPodName(String testPodName) {
        this.testPodName = testPodName;
    }
    
    public Integer getPriority() {
        return priority;
    }
    
    public void setPriority(Integer priority) {
        this.priority = priority;
    }
    
    public Integer getTrainProgress() {
        return trainProgress;
    }
    
    public void setTrainProgress(Integer trainProgress) {
        this.trainProgress = trainProgress;
    }
    
    public String getTrainStatus() {
        return trainStatus;
    }
    
    public void setTrainStatus(String trainStatus) {
        this.trainStatus = trainStatus;
    }
    
    public String getTrainJobId() {
        return trainJobId;
    }
    
    public void setTrainJobId(String trainJobId) {
        this.trainJobId = trainJobId;
    }
    
    public String getTestStatus() {
        return testStatus;
    }
    
    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
    }
    
    public String getTestJobId() {
        return testJobId;
    }
    
    public void setTestJobId(String testJobId) {
        this.testJobId = testJobId;
    }
}