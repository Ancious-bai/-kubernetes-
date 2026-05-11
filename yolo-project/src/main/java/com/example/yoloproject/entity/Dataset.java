package com.example.yoloproject.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "datasets")
public class Dataset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(name = "input_path")
    private String inputPath;

    @Column(nullable = false)
    private Boolean preprocessed = false;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    public Dataset() {}

    public Dataset(String name, String inputPath, String createdBy) {
        this.name = name;
        this.inputPath = inputPath;
        this.createdBy = createdBy;
        this.preprocessed = false;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }
    public Boolean getPreprocessed() { return preprocessed; }
    public void setPreprocessed(Boolean preprocessed) { this.preprocessed = preprocessed; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
}
