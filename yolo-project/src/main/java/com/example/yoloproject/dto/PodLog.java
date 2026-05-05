package com.example.yoloproject.dto;

public class PodLog {
    private String podName;
    private String logContent;

    public PodLog() {}

    public PodLog(String podName, String logContent) {
        this.podName = podName;
        this.logContent = logContent;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getLogContent() {
        return logContent;
    }

    public void setLogContent(String logContent) {
        this.logContent = logContent;
    }
}
