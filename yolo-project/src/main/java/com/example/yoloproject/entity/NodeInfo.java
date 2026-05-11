package com.example.yoloproject.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "cluster_nodes")
public class NodeInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String nodeName;

    @Column
    private String nodeIp;

    @Column
    private String roles;

    @Column
    private Boolean ready;

    @Column
    private String cpuCapacity;

    @Column
    private String cpuAllocatable;

    @Column
    private String memoryCapacity;

    @Column
    private String memoryAllocatable;

    @Column
    private String gpuCapacity;

    @Column
    private String gpuAllocatable;

    @Column
    private String kubeletVersion;

    @Column
    private Boolean schedulable = true;

    @Column(name = "max_concurrent_tasks")
    private Integer maxConcurrentTasks = 1;

    @Column(length = 2000)
    private String labels;

    @Column
    private LocalDateTime lastHeartbeat;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastHeartbeat = LocalDateTime.now();
    }

    public NodeInfo() {}

    public NodeInfo(String nodeName) {
        this.nodeName = nodeName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public String getNodeIp() { return nodeIp; }
    public void setNodeIp(String nodeIp) { this.nodeIp = nodeIp; }

    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }

    public Boolean getReady() { return ready; }
    public void setReady(Boolean ready) { this.ready = ready; }

    public String getCpuCapacity() { return cpuCapacity; }
    public void setCpuCapacity(String cpuCapacity) { this.cpuCapacity = cpuCapacity; }

    public String getCpuAllocatable() { return cpuAllocatable; }
    public void setCpuAllocatable(String cpuAllocatable) { this.cpuAllocatable = cpuAllocatable; }

    public String getMemoryCapacity() { return memoryCapacity; }
    public void setMemoryCapacity(String memoryCapacity) { this.memoryCapacity = memoryCapacity; }

    public String getMemoryAllocatable() { return memoryAllocatable; }
    public void setMemoryAllocatable(String memoryAllocatable) { this.memoryAllocatable = memoryAllocatable; }

    public String getGpuCapacity() { return gpuCapacity; }
    public void setGpuCapacity(String gpuCapacity) { this.gpuCapacity = gpuCapacity; }

    public String getGpuAllocatable() { return gpuAllocatable; }
    public void setGpuAllocatable(String gpuAllocatable) { this.gpuAllocatable = gpuAllocatable; }

    public String getKubeletVersion() { return kubeletVersion; }
    public void setKubeletVersion(String kubeletVersion) { this.kubeletVersion = kubeletVersion; }

    public Boolean getSchedulable() { return schedulable; }
    public void setSchedulable(Boolean schedulable) { this.schedulable = schedulable; }

    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }

    public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Integer getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(Integer maxConcurrentTasks) { this.maxConcurrentTasks = maxConcurrentTasks; }

    @Transient
    private Integer currentTasks = 0;

    public Integer getCurrentTasks() { return currentTasks; }
    public void setCurrentTasks(Integer currentTasks) { this.currentTasks = currentTasks; }

    public Integer getRemainingSlots() {
        return Math.max(0, (maxConcurrentTasks != null ? maxConcurrentTasks : 1) - (currentTasks != null ? currentTasks : 0));
    }
}
