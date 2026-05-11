package com.example.yoloproject.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "node_logs")
public class NodeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_name", nullable = false)
    private String nodeName;

    @Column(name = "record_name", nullable = false)
    private String recordName;

    @Column(name = "data_name", nullable = false)
    private String dataName;

    @Column(name = "task_type", nullable = false, length = 10)
    private String taskType;

    @Column(name = "`status`")
    private String status;

    @Column(name = "epochs")
    private Integer epochs;

    @Column(name = "imgsz")
    private Integer imgsz;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "cpu_usage")
    private String cpuUsage;

    @Column(name = "memory_usage")
    private String memoryUsage;

    @Column(name = "gpu_usage")
    private String gpuUsage;

    public NodeLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getRecordName() { return recordName; }
    public void setRecordName(String recordName) { this.recordName = recordName; }
    public String getDataName() { return dataName; }
    public void setDataName(String dataName) { this.dataName = dataName; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getEpochs() { return epochs; }
    public void setEpochs(Integer epochs) { this.epochs = epochs; }
    public Integer getImgsz() { return imgsz; }
    public void setImgsz(Integer imgsz) { this.imgsz = imgsz; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCpuUsage() { return cpuUsage; }
    public void setCpuUsage(String cpuUsage) { this.cpuUsage = cpuUsage; }
    public String getMemoryUsage() { return memoryUsage; }
    public void setMemoryUsage(String memoryUsage) { this.memoryUsage = memoryUsage; }
    public String getGpuUsage() { return gpuUsage; }
    public void setGpuUsage(String gpuUsage) { this.gpuUsage = gpuUsage; }
}
