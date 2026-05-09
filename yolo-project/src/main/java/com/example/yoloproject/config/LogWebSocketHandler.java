package com.example.yoloproject.config;

import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.TrainingRecordRepository;
import com.example.yoloproject.service.K8sClientService;
import com.example.yoloproject.service.YoloService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    private final TrainingRecordRepository trainingRecordRepository;
    private final YoloService yoloService;
    private final K8sClientService k8sClientService;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(4);
    private final Map<String, StringBuilder> logBuilders = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> retryTasks = new ConcurrentHashMap<>();

    public LogWebSocketHandler(TrainingRecordRepository trainingRecordRepository,
                               YoloService yoloService,
                               K8sClientService k8sClientService) {
        this.trainingRecordRepository = trainingRecordRepository;
        this.yoloService = yoloService;
        this.k8sClientService = k8sClientService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> pathVariables = getUriVariables(session);
        String recordName = pathVariables.get("recordName");
        String type = pathVariables.get("type");

        if (recordName == null || type == null) {
            session.close();
            return;
        }

        String logKey = recordName + "-" + type;
        logBuilders.put(logKey, new StringBuilder());

        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            sendMessage(session, "记录不存在: " + recordName);
            session.close();
            return;
        }

        boolean isTraining = "train".equals(type);
        String status = isTraining ? record.getTrainStatus() : record.getTestStatus();
        String podName = isTraining ? record.getTrainPodName() : record.getTestPodName();
        String typeLabel = isTraining ? "训练" : "测试";

        if ("QUEUED".equals(status)) {
            sendMessage(session, typeLabel + "状态: 排队中\n记录: " + recordName + "\n等待其他任务完成后将自动开始" + typeLabel + "...\n");
            scheduleRetry(session, recordName, type);
            return;
        }

        if (!"RUNNING".equals(status)) {
            loadSavedLog(session, recordName, type);
            return;
        }

        if (podName != null && !podName.isEmpty()) {
            sendMessage(session, typeLabel + "状态: Running\nPod: " + podName + "\n开始获取实时日志...\n");
            startLogStream(session, recordName, type, podName);
        } else {
            sendMessage(session, typeLabel + "状态: Running\nPod创建中，请稍候...\n");
            scheduleRetry(session, recordName, type);
        }
    }

    private void scheduleRetry(WebSocketSession session, String recordName, String type) {
        String logKey = recordName + "-" + type;
        ScheduledFuture<?> future = retryExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!session.isOpen()) {
                    cancelRetry(logKey);
                    return;
                }
                TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
                if (record == null) {
                    sendMessage(session, "记录已不存在\n");
                    cancelRetry(logKey);
                    try { session.close(); } catch (Exception ignored) {}
                    return;
                }

                boolean isTraining = "train".equals(type);
                String status = isTraining ? record.getTrainStatus() : record.getTestStatus();
                String podName = isTraining ? record.getTrainPodName() : record.getTestPodName();
                String typeLabel = isTraining ? "训练" : "测试";

                if ("RUNNING".equals(status) && podName != null && !podName.isEmpty()) {
                    cancelRetry(logKey);
                    sendMessage(session, typeLabel + "状态: Running\nPod: " + podName + "\n开始获取实时日志...\n");
                    startLogStream(session, recordName, type, podName);
                } else if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                    cancelRetry(logKey);
                    loadSavedLog(session, recordName, type);
                }
            } catch (Exception e) {
                cancelRetry(logKey);
            }
        }, 2, 2, TimeUnit.SECONDS);
        retryTasks.put(logKey, future);
    }

    private void cancelRetry(String logKey) {
        ScheduledFuture<?> future = retryTasks.remove(logKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    private Map<String, String> getUriVariables(WebSocketSession session) {
        String uri = session.getUri().toString();
        String[] parts = uri.split("/");
        Map<String, String> vars = new ConcurrentHashMap<>();
        if (parts.length >= 2) {
            vars.put("recordName", parts[parts.length - 2]);
            vars.put("type", parts[parts.length - 1]);
        }
        return vars;
    }

    private void loadSavedLog(WebSocketSession session, String recordName, String type) {
        try {
            Path path = Paths.get(yoloService.LOGS_DIR, recordName + "-" + type + ".txt");
            if (Files.exists(path)) {
                String logContent = Files.readString(path, StandardCharsets.UTF_8);
                sendMessage(session, logContent);
            } else {
                boolean isTraining = "train".equals(type);
                TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
                if (record != null) {
                    String podName = isTraining ? record.getTrainPodName() : record.getTestPodName();
                    if (podName != null && !podName.isEmpty()) {
                        String podLog = yoloService.getPodLogs(podName);
                        if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error")) {
                            sendMessage(session, podLog);
                            saveLog(recordName, type, podLog);
                            try { session.close(); } catch (Exception ignored) {}
                            return;
                        }
                    }
                }
                String statusMsg;
                String status = record != null ? (isTraining ? record.getTrainStatus() : record.getTestStatus()) : "UNKNOWN";
                if ("COMPLETED".equals(status)) {
                    statusMsg = "任务已完成，日志文件暂不可用\n";
                } else if ("FAILED".equals(status)) {
                    statusMsg = "任务失败，日志文件暂不可用\n";
                } else if ("IDLE".equals(status)) {
                    statusMsg = "任务尚未启动\n";
                } else {
                    statusMsg = "日志暂不可用\n";
                }
                sendMessage(session, statusMsg);
            }
            try { session.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            sendMessage(session, "加载日志失败: " + e.getMessage());
            try { session.close(); } catch (Exception ignored) {}
        }
    }

    private void startLogStream(WebSocketSession session, String recordName, String type, String podName) {
        String logKey = recordName + "-" + type;
        boolean isTraining = "train".equals(type);
        String typeLabel = isTraining ? "训练" : "测试";

        sendMessage(session, typeLabel + "状态: Running\nPod: " + podName + "\n开始获取实时日志...\n");
        startPollingLogStream(session, recordName, type, podName);
    }

    private void startPollingLogStream(WebSocketSession session, String recordName, String type, String podName) {
        String logKey = recordName + "-" + type;
        StringBuilder logBuilder = logBuilders.get(logKey);
        java.util.concurrent.atomic.AtomicInteger lastLineCount = new java.util.concurrent.atomic.AtomicInteger(0);

        ScheduledFuture<?> pollFuture = retryExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!session.isOpen()) {
                    cancelRetry(logKey);
                    return;
                }

                String phase = k8sClientService.getPodPhase(podName);
                String logs = k8sClientService.getPodLogs(podName);

                if (logs != null && !logs.isEmpty() && !logs.startsWith("Error")) {
                    String[] lines = logs.split("\n");
                    int currentCount = lastLineCount.get();
                    if (lines.length > currentCount) {
                        StringBuilder newLines = new StringBuilder();
                        for (int i = currentCount; i < lines.length; i++) {
                            newLines.append(lines[i]).append("\n");
                        }
                        lastLineCount.set(lines.length);
                        if (logBuilder != null) logBuilder.append(newLines);
                        sendMessage(session, newLines.toString());
                    }
                }

                if ("Succeeded".equals(phase) || "Failed".equals(phase) || "NOT_FOUND".equals(phase)) {
                    cancelRetry(logKey);
                    if (logBuilder != null && logBuilder.length() > 0) {
                        saveLog(recordName, type, logBuilder.toString());
                    }
                    try { if (session.isOpen()) session.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.error("Polling log stream error: {}", e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);

        retryTasks.put(logKey, pollFuture);
    }

    private void saveLog(String recordName, String type, String logContent) {
        try {
            if (logContent != null && !logContent.isEmpty()) {
                yoloService.saveLogToFile(recordName + "-" + type, logContent);
            }
        } catch (Exception e) {
            log.error("Failed to save log: {}", e.getMessage());
        }
    }

    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (Exception e) {
            log.debug("Failed to send WebSocket message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Map<String, String> pathVariables = getUriVariables(session);
        String recordName = pathVariables.get("recordName");
        String type = pathVariables.get("type");
        if (recordName != null && type != null) {
            String logKey = recordName + "-" + type;

            StringBuilder logBuilder = logBuilders.get(logKey);
            if (logBuilder != null && logBuilder.length() > 0) {
                saveLog(recordName, type, logBuilder.toString());
            }

            logBuilders.remove(logKey);
            cancelRetry(logKey);
        }
    }
}
