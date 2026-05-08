package com.example.yoloproject.service;

import com.example.yoloproject.entity.SystemConfig;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.SystemConfigRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

@Service
public class TrainingSchedulerService {

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private YoloService yoloService;

    private static final String MAX_CONCURRENT_TASKS_KEY = "max.concurrent.tasks";
    private static final String MAX_CONCURRENT_TASKS_DEFAULT = "2";
    private static final String MAX_CONCURRENT_TASKS_DESC = "最大同时训练任务数";
    private static final String DEFAULT_EPOCHS_KEY = "default.epochs";
    private static final String DEFAULT_EPOCHS_VALUE = "2";
    private static final String DEFAULT_EPOCHS_DESC = "默认训练轮数";
    private static final String DEFAULT_IMGSZ_KEY = "default.imgsz";
    private static final String DEFAULT_IMGSZ_VALUE = "640";
    private static final String DEFAULT_IMGSZ_DESC = "默认图像尺寸";

    private volatile int maxConcurrentTasks = 2;
    private volatile int defaultEpochs = 2;
    private volatile int defaultImgsz = 640;

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
        loadMaxConcurrentTasksFromDB();
        loadDefaultEpochsFromDB();
        loadDefaultImgszFromDB();
        schedulerExecutor.submit(this::processQueue);
    }

    private void loadMaxConcurrentTasksFromDB() {
        SystemConfig config = systemConfigRepository.findByConfigKey(MAX_CONCURRENT_TASKS_KEY)
            .orElseGet(() -> {
                SystemConfig defaultConfig = new SystemConfig(
                    MAX_CONCURRENT_TASKS_KEY,
                    MAX_CONCURRENT_TASKS_DEFAULT,
                    MAX_CONCURRENT_TASKS_DESC
                );
                return systemConfigRepository.save(defaultConfig);
            });

        try {
            this.maxConcurrentTasks = Integer.parseInt(config.getConfigValue());
            System.out.println("从数据库加载最大并发训练数: " + this.maxConcurrentTasks);
        } catch (NumberFormatException e) {
            this.maxConcurrentTasks = Integer.parseInt(MAX_CONCURRENT_TASKS_DEFAULT);
            System.out.println("配置值无效，使用默认值: " + this.maxConcurrentTasks);
        }
    }

    public synchronized void setMaxConcurrentTasks(int max) {
        if (max >= 1 && max <= 10) {
            this.maxConcurrentTasks = max;

            SystemConfig config = systemConfigRepository.findByConfigKey(MAX_CONCURRENT_TASKS_KEY)
                .orElse(new SystemConfig(MAX_CONCURRENT_TASKS_KEY, String.valueOf(max), MAX_CONCURRENT_TASKS_DESC));
            config.setConfigValue(String.valueOf(max));
            systemConfigRepository.save(config);

            System.out.println("最大并发训练数已保存到数据库: " + max);
            notifyNewTask();
        }
    }

    public synchronized int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    private void loadDefaultEpochsFromDB() {
        SystemConfig config = systemConfigRepository.findByConfigKey(DEFAULT_EPOCHS_KEY)
            .orElseGet(() -> {
                SystemConfig defaultConfig = new SystemConfig(DEFAULT_EPOCHS_KEY, DEFAULT_EPOCHS_VALUE, DEFAULT_EPOCHS_DESC);
                return systemConfigRepository.save(defaultConfig);
            });
        try {
            this.defaultEpochs = Integer.parseInt(config.getConfigValue());
            System.out.println("从数据库加载默认训练轮数: " + this.defaultEpochs);
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
            System.out.println("默认训练轮数已保存到数据库: " + epochs);
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
            System.out.println("从数据库加载默认图像尺寸: " + this.defaultImgsz);
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
            System.out.println("默认图像尺寸已保存到数据库: " + imgsz);
        }
    }

    public synchronized int getDefaultImgsz() {
        return defaultImgsz;
    }

    public synchronized void addTask(String dataName, Integer epochs, Integer imgsz, String username, Integer priority) {
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

        TrainingTask task = new TrainingTask(dataName, taskPriority, taskEpochs, taskImgsz, recordName);
        taskQueue.add(task);

        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            record = new TrainingRecord(dataName, taskEpochs, taskImgsz, username != null ? username : "system");
        }
        record.setTrainStatus("QUEUED");
        record.setPriority(taskPriority);
        trainingRecordRepository.save(record);

        taskStatusMap.put(recordName, "QUEUED");

        System.out.println("任务已加入队列: " + recordName + ", 优先级: " + taskPriority + ", epochs: " + taskEpochs + ", imgsz: " + taskImgsz);
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
                System.out.println("任务已从队列移除: " + recordName);
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

        System.out.println("任务优先级已更新: " + recordName + ", 新优先级: " + newPriority);
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
            statusList.add(status);
        }

        return statusList;
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronized (this) {
                    while (taskQueue.isEmpty() || runningTasks.size() >= maxConcurrentTasks) {
                        wait();
                    }

                    while (runningTasks.size() < maxConcurrentTasks && !taskQueue.isEmpty()) {
                        TrainingTask task = taskQueue.poll();
                        if (task != null) {
                            runningTasks.add(task);

                            trainingRecordRepository.findByRecordName(task.getRecordName()).ifPresent(r -> {
                                r.setTrainStatus("RUNNING");
                                trainingRecordRepository.save(r);
                            });
                            taskStatusMap.put(task.getRecordName(), "RUNNING");

                            workerExecutor.submit(() -> executeTask(task));
                        }
                    }
                }

                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void executeTask(TrainingTask task) {
        String dataName = task.getDataName();
        String recordName = task.getRecordName();
        try {
            System.out.println("开始训练: " + recordName + ", epochs: " + task.getEpochs() + ", imgsz: " + task.getImgsz());
            String jobId = yoloService.startTraining(dataName, task.getEpochs(), task.getImgsz(), recordName);

            while (!Thread.currentThread().isInterrupted()) {
                if (!trainingRecordRepository.existsByRecordName(recordName)) {
                    System.out.println("训练记录已删除，停止监控: " + recordName);
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
                            System.out.println("训练完成: " + recordName);
                            taskStatusMap.put(recordName, "COMPLETED");
                        } else {
                            System.out.println("训练失败: " + recordName);
                            taskStatusMap.put(recordName, "FAILED");
                        }
                        break;
                    }
                } else {
                    if (!trainingRecordRepository.existsByRecordName(recordName)) {
                        System.out.println("训练记录已删除且JobStatus已清理: " + recordName);
                        break;
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.out.println("训练异常: " + recordName + ", " + e.getMessage());
            e.printStackTrace();
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
                System.out.println("任务已从队列移除: " + recordName);
                return true;
            }
            return false;
        });

        runningTasks.removeIf(task -> {
            if (task.getRecordName().equals(recordName)) {
                System.out.println("运行中任务已标记取消: " + recordName);
                return true;
            }
            return false;
        });

        taskStatusMap.remove(recordName);
        taskProgressMap.remove(recordName);
        notifyNewTask();
    }

    public synchronized void notifyNewTask() {
        notify();
    }

    private static class TrainingTask {
        private final String dataName;
        private int priority;
        private final int epochs;
        private final int imgsz;
        private final String recordName;

        public TrainingTask(String dataName, int priority, int epochs, int imgsz, String recordName) {
            this.dataName = dataName;
            this.priority = priority;
            this.epochs = epochs;
            this.imgsz = imgsz;
            this.recordName = recordName;
        }

        public String getDataName() {
            return dataName;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getEpochs() {
            return epochs;
        }

        public int getImgsz() {
            return imgsz;
        }

        public String getRecordName() {
            return recordName;
        }
    }
}
