package com.example.yoloproject.dto;

public class DatasetStatus {
    private String name;
    private boolean preprocessed;
    private boolean trained;
    private boolean tested;
    private String trainPodName;
    private String testPodName;
    private String trainStatus;
    private String testStatus;
    private Integer priority;
    private Integer trainProgress;
    private String internalTrainStatus;
    private String internalTestStatus;

    public DatasetStatus() {}

    public DatasetStatus(String name) {
        this.name = name;
        this.preprocessed = false;
        this.trained = false;
        this.tested = false;
        this.priority = 5;
        this.trainProgress = 0;
        this.internalTrainStatus = "IDLE";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPreprocessed() {
        return preprocessed;
    }

    public void setPreprocessed(boolean preprocessed) {
        this.preprocessed = preprocessed;
    }

    public boolean isTrained() {
        return trained;
    }

    public void setTrained(boolean trained) {
        this.trained = trained;
    }

    public boolean isTested() {
        return tested;
    }

    public void setTested(boolean tested) {
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

    public String getTrainStatus() {
        return trainStatus;
    }

    public void setTrainStatus(String trainStatus) {
        this.trainStatus = trainStatus;
    }

    public String getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
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

    public String getInternalTrainStatus() {
        return internalTrainStatus;
    }

    public void setInternalTrainStatus(String internalTrainStatus) {
        this.internalTrainStatus = internalTrainStatus;
    }

    public String getInternalTestStatus() {
        return internalTestStatus;
    }

    public void setInternalTestStatus(String internalTestStatus) {
        this.internalTestStatus = internalTestStatus;
    }
}
