package com.example.yoloproject.dto;

public class DatasetStatus {
    private String name;
    private String inputPath;
    private Boolean preprocessed;
    private String createdBy;

    public DatasetStatus(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }
    public Boolean getPreprocessed() { return preprocessed; }
    public void setPreprocessed(Boolean preprocessed) { this.preprocessed = preprocessed; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
