package com.example.yoloproject.service;

import com.example.yoloproject.entity.NodeInfo;
import com.example.yoloproject.entity.SystemConfig;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.SystemConfigRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import com.example.yoloproject.service.K8sClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class TrainingSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TrainingSchedulerService.class);

    @Autowired
    private K8sClientService k8sClientService;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private YoloService yoloService;

    @Autowired
    private NodeManagementService nodeManagementService;

    private static final String DEFAULT_EPOCHS_KEY = "default.epochs";
    private static final String DEFAULT_EPOCHS_VALUE = "2";
    private static final String DEFAULT_EPOCHS_DESC = "默认训练轮数";
    private static final String DEFAULT_IMGSZ_KEY = "default.imgsz";
    private static final String DEFAULT_IMGSZ_VALUE = "640";
    private static final String DEFAULT_IMGSZ_DESC = "默认图像尺寸";
    private static final String SCHEDULING_MODE_KEY = "scheduling.mode";
    private static final String SCHEDULING_MODE_DEFAULT = "auto";
    private static final String SCHEDULING_MODE_DESC = "调度模式(auto/manual)";

    private volatile int defaultEpochs = 2;
    private volatile int defaultImgsz = 640;
    private volatile String schedulingMode = "auto";

    private final PriorityQueue<TrainingTask> taskQueue = new PriorityQueue<>(
        Comparator.comparingInt(TrainingTask::getPriority)
    );

    private final Set<TrainingTask> runningTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, String> taskStatusMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> taskProgressMap = new ConcurrentHashMap<>();

    private final ExecutorService schedulerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool();

    @PostConstruct
    public void init() {
        loadDefaultEpochsFromDB();
        loadDefaultImgszFromDB();
        loadSchedulingModeFromDB();
        schedulerExecutor.submit(this::processQueue);
    }

    public int getTotalMaxConcurrentTasks() {
        return nodeManagementService.getSchedulableNodes().stream()
                .filter(NodeInfo::getReady)
                .mapToInt(n -> n.getMaxConcurrentTasks() != null ? n.getMaxConcurrentTasks() : 1)
                .sum();
    }

    public int getRunningTaskCountOnNode(String nodeName) {
        return (int) runningTasks.stream()
                .filter(t -> nodeName.equals(t.getTargetNode()))
                .count();
    }

    public int getNodeMaxConcurrent(String nodeName) {
        return nodeManagementService.getNodeByName(nodeName)
                .map(n -> n.getMaxConcurrentTasks() != null ? n.getMaxConcurrentTasks() : 1)
                .orElse(1);
    }

    private NodeInfo selectNodeForTask(Map<String, String> gpuResources) {
        List<NodeInfo> candidates = nodeManagementService.getSchedulableNodes().stream()
                .filter(NodeInfo::getReady)
                .filter(n -> {
                    int max = n.getMaxConcurrentTasks() != null ? n.getMaxConcurrentTasks() : 1;
                    return getRunningTaskCountOnNode(n.getNodeName()) < max;
                })
                .collect(Collectors.toList());

        if (gpuResources != null && !gpuResources.isEmpty()) {
            candidates = candidates.stream()
                    .filter(n -> n.getGpuAllocatable() != null && !n.getGpuAllocatable().equals("0"))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) return null;

        return candidates.stream()
                .min(Comparator.comparingInt(n -> getRunningTaskCountOnNode(n.getNodeName())))
                .orElse(null);
    }

    private void loadDefaultEpochsFromDB() {
        SystemConfig config = systemConfigRepository.findByConfigKey(DEFAULT_EPOCHS_KEY)
            .orElseGet(() -> {
                SystemConfig defaultConfig = new SystemConfig(DEFAULT_EPOCHS_KEY, DEFAULT_EPOCHS_VALUE, DEFAULT_EPOCHS_DESC);
                return systemConfigRepository.save(defaultConfig);
            });
        try {
            this.defaultEpochs = Integer.parseInt(config.getConfigValue());
        } catch (NumberFormatException e) {
            this.defaultEpochs = Integer.parseInt(DEFAULT_EPOCHS_VALUE);
        }
    }

    public synchronized void setDefaultEpochs(int epochs) {
        if (epochs >= 1 && epochs <= 1000) {
            this.defaultEpochs = epochs;
            SystemConfig config = systemConfigRepository.findByConfigKey(DEFAULT_EPOCHS_KEY)
                .orElse(new SystemConfig(DEFAULT_EPOCHS_KEY, String.valueOf(epochs), DEFAULT_EPOCHS_DESC));
            config.setConfigValue(String.valueOf(epochs));
            systemConfigRepository.save(config);
        }
    }

    public synchronized int getDefaultEpochs() {
        return defaultEpochs;
    }

    private void loadDefaultImgszFromDB() {
        SystemConfig config = systemConfigRepository.findByConfigKey(DEFAULT_IMGSZ_KEY)
            .orElseGet(() -> {
                SystemConfig defaultConfig = new SystemConfig(DEFAULT_IMGSZ_KEY, DEFAULT_IMGSZ_VALUE, DEFAULT_IMGSZ_DESC);
                return systemConfigRepository.save(defaultConfig);
            });
        try {
            this.defaultImgsz = Integer.parseInt(config.getConfigValue());
        } catch (NumberFormatException e) {
            this.defaultImgsz = Integer.parseInt(DEFAULT_IMGSZ_VALUE);
        }
    }

    public synchronized void setDefaultImgsz(int imgsz) {
        if (imgsz >= 32 && imgsz <= 1280) {
            this.defaultImgsz = imgsz;
            SystemConfig config = systemConfigRepository.findByConfigKey(DEFAULT_IMGSZ_KEY)
                .orElse(new SystemConfig(DEFAULT_IMGSZ_KEY, String.valueOf(imgsz), DEFAULT_IMGSZ_DESC));
            config.setConfigValue(String.valueOf(imgsz));
            systemConfigRepository.save(config);
        }
    }

    public synchronized int getDefaultImgsz() {
        return defaultImgsz;
    }

    private void loadSchedulingModeFromDB() {
        SystemConfig config = systemConfigRepository.findByConfigKey(SCHEDULING_MODE_KEY)
            .orElseGet(() -> {
                SystemConfig defaultConfig = new SystemConfig(SCHEDULING_MODE_KEY, SCHEDULING_MODE_DEFAULT, SCHEDULING_MODE_DESC);
                return systemConfigRepository.save(defaultConfig);
            });
        this.schedulingMode = config.getConfigValue();
        log.info("Loaded schedulingMode: {}", this.schedulingMode);
    }

    public synchronized void setSchedulingMode(String mode) {
        if ("auto".equals(mode) || "manual".equals(mode)) {
            this.schedulingMode = mode;
            SystemConfig config = systemConfigRepository.findByConfigKey(SCHEDULING_MODE_KEY)
                .orElse(new SystemConfig(SCHEDULING_MODE_KEY, mode, SCHEDULING_MODE_DESC));
            config.setConfigValue(mode);
            systemConfigRepository.save(config);
            log.info("SchedulingMode saved: {}", mode);
            notifyNewTask();
        }
    }

    public synchronized String getSchedulingMode() {
        return schedulingMode;
    }

    public synchronized void addTask(String dataName, Integer epochs, Integer imgsz, String username, Integer priority) {
        addTask(dataName, epochs, imgsz, username, priority, null, null, null);
    }

    public synchronized void addTask(String dataName, Integer epochs, Integer imgsz, String username,
                                      Integer priority, String targetNode, Map<String, String> nodeSelector,
                                      Map<String, String> gpuResources) {
        int taskEpochs = (epochs != null && epochs > 0) ? epochs : defaultEpochs;
        int taskImgsz = (imgsz != null && imgsz > 0) ? imgsz : defaultImgsz;
        int taskPriority = (priority != null && priority >= 1 && priority <= 10) ? priority : 5;
        String recordName = dataName + "-e" + taskEpochs + "-i" + taskImgsz;

        for (TrainingTask task : taskQueue) {
            if (task.getRecordName().equals(recordName)) {
                throw new IllegalArgumentException("训练记录已在队列中: " + recordName);
            }
        }
        for (TrainingTask task : runningTasks) {
            if (task.getRecordName().equals(recordName)) {
                throw new IllegalArgumentException("训练记录正在运行: " + recordName);
            }
        }

        if (trainingRecordRepository.existsByRecordName(recordName)) {
            TrainingRecord existing = trainingRecordRepository.findByRecordName(recordName).orElse(null);
            if (existing != null && "RUNNING".equals(existing.getTrainStatus())) {
                throw new IllegalArgumentException("训练记录正在运行: " + recordName);
            }
            if (existing != null && "QUEUED".equals(existing.getTrainStatus())) {
                throw new IllegalArgumentException("训练记录已在队列中: " + recordName);
            }
        }

        String effectiveNode = targetNode;
        Map<String, String> effectiveNodeSelector = nodeSelector;
        Map<String, String> effectiveGpuResources = gpuResources;

        if ("auto".equals(schedulingMode) && effectiveNode == null) {
            NodeInfo selectedNode = selectNodeForTask(gpuResources);
            if (selectedNode != null) {
                effectiveNode = selectedNode.getNodeName();
                log.info("Auto-scheduling: task {} assigned to node {}", recordName, effectiveNode);
            }
        }

        TrainingTask task = new TrainingTask(dataName, taskPriority, taskEpochs, taskImgsz, recordName,
                effectiveNode, effectiveNodeSelector, effectiveGpuResources);
        taskQueue.add(task);

        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            record = new TrainingRecord(dataName, taskEpochs, taskImgsz, username != null ? username : "system");
        }
        record.setTrainStatus("QUEUED");
        record.setPriority(taskPriority);
        trainingRecordRepository.save(record);

        taskStatusMap.put(recordName, "QUEUED");
        log.info("Task added to queue: {}, priority: {}, epochs: {}, imgsz: {}, node: {}",
                recordName, taskPriority, taskEpochs, taskImgsz, effectiveNode);
        notifyNewTask();
    }

    public synchronized void removeTask(String recordName) {
        Iterator<TrainingTask> iterator = taskQueue.iterator();
        while (iterator.hasNext()) {
            TrainingTask task = iterator.next();
            if (task.getRecordName().equals(recordName)) {
                iterator.remove();
                trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                    r.setTrainStatus("IDLE");
                    trainingRecordRepository.save(r);
                });
                taskStatusMap.put(recordName, "IDLE");
                log.info("Task removed from queue: {}", recordName);
                return;
            }
        }
        throw new IllegalArgumentException("任务不在队列中: " + recordName);
    }

    public synchronized void updatePriority(String recordName, int newPriority) {
        if (newPriority < 1 || newPriority > 10) {
            throw new IllegalArgumentException("优先级必须在1-10之间");
        }
        for (TrainingTask task : taskQueue) {
            if (task.getRecordName().equals(recordName)) {
                task.setPriority(newPriority);
                reorderQueue();
                break;
            }
        }
        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
            r.setPriority(newPriority);
            trainingRecordRepository.save(r);
        });
        log.info("Task priority updated: {} -> {}", recordName, newPriority);
    }

    private void reorderQueue() {
        List<TrainingTask> tasks = new ArrayList<>(taskQueue);
        taskQueue.clear();
        taskQueue.addAll(tasks);
    }

    public String getTaskStatus(String recordName) {
        return taskStatusMap.getOrDefault(recordName, "IDLE");
    }

    public int getTaskProgress(String recordName) {
        return taskProgressMap.getOrDefault(recordName, 0);
    }

    public List<Map<String, Object>> getQueueStatus() {
        List<Map<String, Object>> statusList = new ArrayList<>();

        for (TrainingTask task : runningTasks) {
            Map<String, Object> status = new HashMap<>();
            status.put("dataName", task.getDataName());
            status.put("recordName", task.getRecordName());
            status.put("status", "RUNNING");
            status.put("priority", task.getPriority());
            status.put("progress", taskProgressMap.getOrDefault(task.getRecordName(), 0));
            status.put("position", "running");
            status.put("assignedNode", task.getTargetNode());
            statusList.add(status);
        }

        List<TrainingTask> tasks = new ArrayList<>(taskQueue);
        Collections.sort(tasks, Comparator.comparingInt(TrainingTask::getPriority));
        int position = 1;
        for (TrainingTask task : tasks) {
            Map<String, Object> status = new HashMap<>();
            status.put("dataName", task.getDataName());
            status.put("recordName", task.getRecordName());
            status.put("status", "QUEUED");
            status.put("priority", task.getPriority());
            status.put("progress", taskProgressMap.getOrDefault(task.getRecordName(), 0));
            status.put("position", position++);
            status.put("assignedNode", task.getTargetNode());
            statusList.add(status);
        }

        return statusList;
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronized (this) {
                    int totalMax = getTotalMaxConcurrentTasks();
                    while (taskQueue.isEmpty() || runningTasks.size() >= totalMax) {
                        wait();
                        totalMax = getTotalMaxConcurrentTasks();
                    }

                    boolean scheduledAny = false;
                    while (runningTasks.size() < totalMax && !taskQueue.isEmpty()) {
                        TrainingTask task = taskQueue.peek();
                        if (task == null) break;

                        String targetNode = task.getTargetNode();
                        boolean canSchedule = false;

                        if (targetNode != null && !targetNode.isEmpty()) {
                            int nodeMax = getNodeMaxConcurrent(targetNode);
                            int nodeRunning = getRunningTaskCountOnNode(targetNode);
                            canSchedule = nodeRunning < nodeMax;
                        } else {
                            NodeInfo selected = selectNodeForTask(task.getGpuResources());
                            if (selected != null) {
                                task.setTargetNode(selected.getNodeName());
                                canSchedule = true;
                            } else {
                                canSchedule = false;
                            }
                        }

                        if (canSchedule) {
                            taskQueue.poll();
                            runningTasks.add(task);
                            trainingRecordRepository.findByRecordName(task.getRecordName()).ifPresent(r -> {
                                r.setTrainStatus("RUNNING");
                                trainingRecordRepository.save(r);
                            });
                            taskStatusMap.put(task.getRecordName(), "RUNNING");
                            workerExecutor.submit(() -> executeTask(task));
                            scheduledAny = true;
                        } else {
                            break;
                        }
                    }

                    if (!scheduledAny && !taskQueue.isEmpty()) {
                        wait(5000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in scheduler loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void executeTask(TrainingTask task) {
        String dataName = task.getDataName();
        String recordName = task.getRecordName();
        try {
            log.info("Starting training: {}, epochs: {}, imgsz: {}, node: {}",
                    recordName, task.getEpochs(), task.getImgsz(), task.getTargetNode());

            String jobId;
            jobId = yoloService.startTrainingOnNode(
                    dataName, task.getEpochs(), task.getImgsz(), recordName,
                    task.getTargetNode(), task.getNodeSelector(), task.getGpuResources()
            );

            while (!Thread.currentThread().isInterrupted()) {
                if (!trainingRecordRepository.existsByRecordName(recordName)) {
                    log.info("Training record deleted, stopping monitor: {}", recordName);
                    yoloService.removeJobStatus(jobId);
                    break;
                }

                var status = yoloService.getJobStatus(jobId);
                if (status != null) {
                    taskProgressMap.put(recordName, status.getProgress());
                    trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                        r.setTrainProgress(status.getProgress());
                        trainingRecordRepository.save(r);
                    });

                    if ("COMPLETED".equals(status.getStatus()) ||
                        "DONE".equals(status.getStatus()) ||
                        "FAILED".equals(status.getStatus())) {

                        if ("COMPLETED".equals(status.getStatus()) || "DONE".equals(status.getStatus())) {
                            log.info("Training completed: {}", recordName);
                            taskStatusMap.put(recordName, "COMPLETED");
                        } else {
                            log.info("Training failed: {}", recordName);
                            taskStatusMap.put(recordName, "FAILED");
                        }
                        break;
                    }
                } else {
                    if (!trainingRecordRepository.existsByRecordName(recordName)) {
                        log.info("Training record deleted and JobStatus cleaned: {}", recordName);
                        break;
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            log.error("Training exception: {} - {}", recordName, e.getMessage());
            taskStatusMap.put(recordName, "FAILED");
        } finally {
            synchronized (this) {
                runningTasks.remove(task);
                taskStatusMap.remove(recordName);
                taskProgressMap.remove(recordName);
                notifyNewTask();
            }
        }
    }

    public synchronized void cancelTask(String recordName) {
        taskQueue.removeIf(task -> {
            if (task.getRecordName().equals(recordName)) {
                log.info("Task removed from queue: {}", recordName);
                return true;
            }
            return false;
        });

        runningTasks.removeIf(task -> {
            if (task.getRecordName().equals(recordName)) {
                log.info("Running task cancelled, deleting K8s jobs: {}", recordName);
                try {
                    k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(recordName + "-train-job"));
                    k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(recordName + "-train-job"));
                } catch (Exception e) {
                    log.warn("Failed to delete train job for cancelled task {}: {}", recordName, e.getMessage());
                }
                return true;
            }
            return false;
        });

        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
            if ("QUEUED".equals(r.getTrainStatus()) || "RUNNING".equals(r.getTrainStatus())) {
                r.setTrainStatus("IDLE");
                trainingRecordRepository.save(r);
            }
        });

        taskStatusMap.remove(recordName);
        taskProgressMap.remove(recordName);
        notifyNewTask();
    }

    public synchronized void notifyNewTask() {
        notify();
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("totalMaxConcurrentTasks", getTotalMaxConcurrentTasks());
        config.put("defaultEpochs", defaultEpochs);
        config.put("defaultImgsz", defaultImgsz);
        config.put("schedulingMode", schedulingMode);
        return config;
    }

    private static class TrainingTask {
        private final String dataName;
        private int priority;
        private final int epochs;
        private final int imgsz;
        private final String recordName;
        private String targetNode;
        private final Map<String, String> nodeSelector;
        private final Map<String, String> gpuResources;

        public TrainingTask(String dataName, int priority, int epochs, int imgsz, String recordName,
                            String targetNode, Map<String, String> nodeSelector, Map<String, String> gpuResources) {
            this.dataName = dataName;
            this.priority = priority;
            this.epochs = epochs;
            this.imgsz = imgsz;
            this.recordName = recordName;
            this.targetNode = targetNode;
            this.nodeSelector = nodeSelector;
            this.gpuResources = gpuResources;
        }

        public String getDataName() { return dataName; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getEpochs() { return epochs; }
        public int getImgsz() { return imgsz; }
        public String getRecordName() { return recordName; }
        public String getTargetNode() { return targetNode; }
        public void setTargetNode(String targetNode) { this.targetNode = targetNode; }
        public Map<String, String> getNodeSelector() { return nodeSelector; }
        public Map<String, String> getGpuResources() { return gpuResources; }
    }
}
