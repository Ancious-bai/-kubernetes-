package com.example.yoloproject.dto;

public class MetricsResponse {
    private String map50;
    private String map95;
    private String precision;
    private String recall;
    private String f1Score;

    public MetricsResponse() {}

    public String getMap50() {
        return map50;
    }

    public void setMap50(String map50) {
        this.map50 = map50;
    }

    public String getMap95() {
        return map95;
    }

    public void setMap95(String map95) {
        this.map95 = map95;
    }

    public String getPrecision() {
        return precision;
    }

    public void setPrecision(String precision) {
        this.precision = precision;
    }

    public String getRecall() {
        return recall;
    }

    public void setRecall(String recall) {
        this.recall = recall;
    }

    public String getF1Score() {
        return f1Score;
    }

    public void setF1Score(String f1Score) {
        this.f1Score = f1Score;
    }
}
