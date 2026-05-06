package com.example.yoloproject.service;

import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.entity.SystemConfig;
import com.example.yoloproject.repository.DatasetRepository;
import com.example.yoloproject.repository.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

@Service
public class TrainingSchedulerService {

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

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

    // 优先级队列：数字越小优先级越高
    private final PriorityQueue<TrainingTask> taskQueue = new PriorityQueue<>(
        Comparator.comparingInt(TrainingTask::getPriority)
    );

    // 正在运行的任务集合
    private final Set<TrainingTask> runningTasks = ConcurrentHashMap.newKeySet();

    // 任务状态映射
    private final Map<String, String> taskStatusMap = new ConcurrentHashMap<>();

    // 任务进度映射
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
                // 如果不存在，创建默认配置
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
            
            // 保存到数据库
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

    public synchronized void addTask(String dataName, Integer epochs, Integer imgsz) {
        Dataset dataset = datasetRepository.findByName(dataName).orElse(null);
        if (dataset == null) {
            throw new IllegalArgumentException("数据集不存在: " + dataName);
        }

        for (TrainingTask task : taskQueue) {
            if (task.getDataName().equals(dataName)) {
                throw new IllegalArgumentException("任务已在队列中: " + dataName);
            }
        }
        for (TrainingTask task : runningTasks) {
            if (task.getDataName().equals(dataName)) {
                throw new IllegalArgumentException("任务正在运行: " + dataName);
            }
        }

        int taskEpochs = (epochs != null && epochs > 0) ? epochs : defaultEpochs;
        int taskImgsz = (imgsz != null && imgsz > 0) ? imgsz : defaultImgsz;
        TrainingTask task = new TrainingTask(dataName, dataset.getPriority(), taskEpochs, taskImgsz);
        taskQueue.add(task);

        datasetRepository.findByName(dataName).ifPresent(d -> {
            d.setTrainStatus("QUEUED");
            datasetRepository.save(d);
        });
        taskStatusMap.put(dataName, "QUEUED");

        System.out.println("任务已加入队列: " + dataName + ", 优先级: " + dataset.getPriority() + ", epochs: " + taskEpochs + ", imgsz: " + taskImgsz);
        notifyNewTask();
    }

    public synchronized void removeTask(String dataName) {
        Iterator<TrainingTask> iterator = taskQueue.iterator();
        while (iterator.hasNext()) {
            TrainingTask task = iterator.next();
            if (task.getDataName().equals(dataName)) {
                iterator.remove();
                datasetRepository.findByName(dataName).ifPresent(d -> {
                    d.setTrainStatus("IDLE");
                    datasetRepository.save(d);
                });
                taskStatusMap.put(dataName, "IDLE");
                System.out.println("任务已从队列移除: " + dataName);
                return;
            }
        }
        throw new IllegalArgumentException("任务不在队列中: " + dataName);
    }

    public synchronized void updatePriority(String dataName, int newPriority) {
        if (newPriority < 1 || newPriority > 10) {
            throw new IllegalArgumentException("优先级必须在1-10之间");
        }

        // 更新队列中的任务优先级
        for (TrainingTask task : taskQueue) {
            if (task.getDataName().equals(dataName)) {
                task.setPriority(newPriority);
                reorderQueue();
                break;
            }
        }

        // 更新数据库中的优先级
        datasetRepository.findByName(dataName).ifPresent(d -> {
            d.setPriority(newPriority);
            datasetRepository.save(d);
        });

        System.out.println("任务优先级已更新: " + dataName + ", 新优先级: " + newPriority);
    }

    private void reorderQueue() {
        List<TrainingTask> tasks = new ArrayList<>(taskQueue);
        taskQueue.clear();
        taskQueue.addAll(tasks);
    }

    public String getTaskStatus(String dataName) {
        return taskStatusMap.getOrDefault(dataName, "IDLE");
    }

    public int getTaskProgress(String dataName) {
        return taskProgressMap.getOrDefault(dataName, 0);
    }

    public List<Map<String, Object>> getQueueStatus() {
        List<Map<String, Object>> statusList = new ArrayList<>();

        // 正在运行的任务
        for (TrainingTask task : runningTasks) {
            Map<String, Object> status = new HashMap<>();
            status.put("dataName", task.getDataName());
            status.put("status", "RUNNING");
            status.put("priority", task.getPriority());
            status.put("progress", taskProgressMap.getOrDefault(task.getDataName(), 0));
            status.put("position", "running");
            statusList.add(status);
        }

        // 队列中的任务
        List<TrainingTask> tasks = new ArrayList<>(taskQueue);
        Collections.sort(tasks, Comparator.comparingInt(TrainingTask::getPriority));
        int position = 1;
        for (TrainingTask task : tasks) {
            Map<String, Object> status = new HashMap<>();
            status.put("dataName", task.getDataName());
            status.put("status", "QUEUED");
            status.put("priority", task.getPriority());
            status.put("progress", taskProgressMap.getOrDefault(task.getDataName(), 0));
            status.put("position", position++);
            statusList.add(status);
        }

        return statusList;
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronized (this) {
                    // 检查是否可以启动新任务
                    while (taskQueue.isEmpty() || runningTasks.size() >= maxConcurrentTasks) {
                        wait();
                    }

                    // 启动尽可能多的任务
                    while (runningTasks.size() < maxConcurrentTasks && !taskQueue.isEmpty()) {
                        TrainingTask task = taskQueue.poll();
                        if (task != null) {
                            runningTasks.add(task);

                            // 更新状态
                            datasetRepository.findByName(task.getDataName()).ifPresent(d -> {
                                d.setTrainStatus("RUNNING");
                                datasetRepository.save(d);
                            });
                            taskStatusMap.put(task.getDataName(), "RUNNING");

                            // 在单独的线程中执行任务
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
        try {
            System.out.println("开始训练: " + dataName + ", epochs: " + task.getEpochs() + ", imgsz: " + task.getImgsz());
            String jobId = yoloService.startTraining(dataName, task.getEpochs(), task.getImgsz());

            // 轮询训练状态
            while (!Thread.currentThread().isInterrupted()) {
                var status = yoloService.getJobStatus(jobId);
                if (status != null) {
                    taskProgressMap.put(dataName, status.getProgress());
                    datasetRepository.findByName(dataName).ifPresent(d -> {
                        d.setTrainProgress(status.getProgress());
                        datasetRepository.save(d);
                    });

                    if ("COMPLETED".equals(status.getStatus()) || 
                        "DONE".equals(status.getStatus()) || 
                        "FAILED".equals(status.getStatus())) {
                        
                        if ("COMPLETED".equals(status.getStatus()) || "DONE".equals(status.getStatus())) {
                            System.out.println("训练完成: " + dataName);
                            taskStatusMap.put(dataName, "COMPLETED");
                        } else {
                            System.out.println("训练失败: " + dataName);
                            taskStatusMap.put(dataName, "FAILED");
                        }
                        break;
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.out.println("训练异常: " + dataName + ", " + e.getMessage());
            e.printStackTrace();
            taskStatusMap.put(dataName, "FAILED");
        } finally {
            // 任务完成，从运行列表中移除
            synchronized (this) {
                runningTasks.remove(task);
                notifyNewTask();
            }
        }
    }

    public synchronized void notifyNewTask() {
        notify();
    }

    // 内部类：训练任务
    private static class TrainingTask {
        private final String dataName;
        private int priority;
        private final int epochs;
        private final int imgsz;

        public TrainingTask(String dataName, int priority, int epochs, int imgsz) {
            this.dataName = dataName;
            this.priority = priority;
            this.epochs = epochs;
            this.imgsz = imgsz;
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
    }
}
