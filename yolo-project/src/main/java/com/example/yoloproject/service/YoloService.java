package com.example.yoloproject.service;

import com.example.yoloproject.dto.DatasetStatus;
import com.example.yoloproject.dto.JobStatus;
import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.DatasetRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import io.kubernetes.client.openapi.models.V1Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YoloService {

    private static final Logger log = LoggerFactory.getLogger(YoloService.class);

    private final Map<String, JobStatus> jobStatusMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Value("${app.project-root:./}")
    private String projectRootConfig;

    @Value("${app.training-image:yolov8-project:latest}")
    private String trainingImage;

    @Value("${app.pvc-name:yolo-workspace}")
    private String pvcName;

    @Value("${app.mount-path:/app}")
    private String mountPath;

    @Value("${app.use-pvc:false}")
    private boolean usePvc;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Lazy
    private TrainingSchedulerService trainingSchedulerService;

    @Autowired
    private K8sClientService k8sClientService;

    private String PROJECT_ROOT;
    public String LOGS_DIR;

    @jakarta.annotation.PostConstruct
    public void init() {
        if (projectRootConfig != null && !projectRootConfig.isEmpty() && !"./".equals(projectRootConfig)) {
            PROJECT_ROOT = projectRootConfig;
        } else {
            PROJECT_ROOT = System.getProperty("user.dir");
        }
        PROJECT_ROOT = PROJECT_ROOT.replace("\\", "/");
        if (!PROJECT_ROOT.endsWith("/")) {
            PROJECT_ROOT += "/";
        }
        LOGS_DIR = PROJECT_ROOT + "logs";
        log.info("YoloService initialized, PROJECT_ROOT={}, trainingImage={}, pvcName={}, usePvc={}",
                PROJECT_ROOT, trainingImage, pvcName, usePvc);
    }

    public String getProjectRoot() {
        return PROJECT_ROOT;
    }

    public String startPreprocess(String inputDir) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        if (usePvc && k8sClientService.isReady()) {
            startPreprocessAsK8sJob(jobId, inputDir);
        } else {
            startPreprocessLocal(jobId, inputDir);
        }

        return jobId;
    }

    private void startPreprocessAsK8sJob(String jobId, String inputDir) {
        executorService.submit(() -> {
            String podName = null;
            String dataName = new File(inputDir).getName();
            try {
                String preprocessJobName = K8sClientService.sanitizeK8sName(dataName + "-preprocess-job");

                k8sClientService.deleteJob(preprocessJobName);
                Thread.sleep(1000);

                V1Job job = k8sClientService.buildPreprocessJob(
                        dataName + "-preprocess-job", dataName, inputDir,
                        trainingImage, pvcName, mountPath
                );
                k8sClientService.createJob(job);
                log.info("Preprocess job created: {}", preprocessJobName);

                for (int i = 0; i < 30; i++) {
                    Thread.sleep(1000);
                    podName = k8sClientService.getFirstPodNameByJob(preprocessJobName);
                    if (podName != null) break;
                }

                if (podName != null) {
                    String finalPodName = podName;
                    while (true) {
                        try {
                            Thread.sleep(2000);
                            String phase = k8sClientService.getPodPhase(finalPodName);
                            
                            if ("Succeeded".equals(phase) || "Failed".equals(phase) || "NOT_FOUND".equals(phase)) {
                                break;
                            }
                            
                            if ("Running".equals(phase) || "Pending".equals(phase)) {
                                try {
                                    String tailLog = k8sClientService.getPodLogs(finalPodName, 20);
                                    if (tailLog != null && !tailLog.isEmpty()) {
                                        updateStatus(jobId, "RUNNING", 50, tailLog);
                                    }
                                } catch (Exception logEx) {
                                    updateStatus(jobId, "RUNNING", 50, "Pod is starting... (" + phase + ")\n");
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    String fullLog = "";
                    try {
                        fullLog = k8sClientService.getPodLogs(finalPodName);
                    } catch (Exception e) {
                        fullLog = "Could not retrieve pod logs";
                    }
                    String finalLog = fullLog != null ? fullLog : "";
                    String actualPhase = k8sClientService.getPodPhase(finalPodName);

                    if ("Succeeded".equals(actualPhase)) {
                        updateStatus(jobId, "COMPLETED", 100, finalLog + "\nPreprocess completed successfully\n");
                        datasetRepository.findByName(dataName).ifPresent(d -> {
                            d.setPreprocessed(true);
                            datasetRepository.save(d);
                        });
                    } else {
                        updateStatus(jobId, "FAILED", 0, finalLog + "\nPreprocess failed (pod phase: " + actualPhase + ")\n");
                        deleteDataset(dataName);
                    }
                } else {
                    updateStatus(jobId, "FAILED", 0, "Failed to find pod for preprocess job");
                    deleteDataset(dataName);
                }
            } catch (Exception e) {
                log.error("Preprocess K8s job exception: {}", e.getMessage(), e);
                updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
                deleteDataset(dataName);
            }
        });
    }

    private void startPreprocessLocal(String jobId, String inputDir) {
        executorService.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python3", "preprocess.py", "--input_dir", inputDir);
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder logBuilder = new StringBuilder();
                String line;

                logBuilder.append("Running: python3 preprocess.py --input_dir ").append(inputDir).append("\n");
                logBuilder.append("----------------------------------------------------\n");

                while ((line = reader.readLine()) != null) {
                    logBuilder.append(line).append("\n");
                    updateStatus(jobId, "RUNNING", 50, logBuilder.toString());
                }

                int exitCode = process.waitFor();
                logBuilder.append("----------------------------------------------------\n");
                if (exitCode == 0) {
                    logBuilder.append("Command completed successfully\n");
                    updateStatus(jobId, "COMPLETED", 100, logBuilder.toString());

                    String dataName = new File(inputDir).getName();
                    datasetRepository.findByName(dataName).ifPresent(d -> {
                        d.setPreprocessed(true);
                        datasetRepository.save(d);
                    });
                } else {
                    logBuilder.append("Command failed with exit code: ").append(exitCode).append("\n");
                    logBuilder.append("Auto-deleting dataset...\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());

                    String dataName = new File(inputDir).getName();
                    deleteDataset(dataName);
                }
            } catch (Exception e) {
                StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("Error: ").append(e.getMessage()).append("\n");
                logBuilder.append("Auto-deleting dataset...\n");
                updateStatus(jobId, "FAILED", 0, logBuilder.toString());

                String dataName = new File(inputDir).getName();
                deleteDataset(dataName);
            }
        });
    }

    public String startTraining(String dataName, int epochs, int imgsz, String explicitRecordName) {
        String recordName = (explicitRecordName != null && !explicitRecordName.isEmpty())
                ? explicitRecordName : dataName + "-e" + epochs + "-i" + imgsz;
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            record = new TrainingRecord(dataName, epochs, imgsz, "system");
        }
        record.setTrainJobId(jobId);
        record.setTrainStatus("RUNNING");
        record.setTrainProgress(0);
        trainingRecordRepository.save(record);

        executorService.submit(() -> {
            String podName = null;
            try {
                String trainJobName = K8sClientService.sanitizeK8sName(recordName + "-train-job");
                k8sClientService.deleteJob(trainJobName);
                Thread.sleep(1000);

                String effectivePvc = usePvc ? pvcName : null;
                V1Job job = k8sClientService.buildTrainingJob(
                        recordName + "-train-job", dataName, recordName,
                        epochs, imgsz, trainingImage,
                        effectivePvc, mountPath,
                        null, null, null
                );
                k8sClientService.createJob(job);
                log.info("Training job created: {}", trainJobName);

                for (int i = 0; i < 30; i++) {
                    Thread.sleep(1000);
                    podName = k8sClientService.getFirstPodNameByJob(trainJobName);
                    if (podName != null) {
                        final String finalPodName = podName;
                        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                            r.setTrainPodName(finalPodName);
                            trainingRecordRepository.save(r);
                        });
                        break;
                    }
                }

                if (podName != null) {
                    final String finalPodName = podName;
                    int progress = 0;

                    while (true) {
                        try {
                            Thread.sleep(2000);

                            String tailLog = k8sClientService.getPodLogs(finalPodName, 10);
                            int parsedProgress = parseEpochProgress(tailLog);
                            if (parsedProgress > 0) {
                                progress = parsedProgress;
                                final int currentProgress = progress;
                                trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                    r.setTrainProgress(currentProgress);
                                    trainingRecordRepository.save(r);
                                });
                            }

                            updateStatus(jobId, "RUNNING", progress, "", finalPodName);

                            String phase = k8sClientService.getPodPhase(finalPodName);
                            if ("Succeeded".equals(phase) || "Failed".equals(phase) || "NOT_FOUND".equals(phase)) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    String fullLog = k8sClientService.getPodLogs(finalPodName);
                    if (fullLog == null || fullLog.isEmpty() || fullLog.startsWith("Error")) {
                        fullLog = k8sClientService.getPodLogs(finalPodName);
                    }

                    String finalLog = fullLog != null ? fullLog : "";
                    String finalLogLower = finalLog.toLowerCase();

                    boolean hasFatalError = finalLogLower.contains("fatal error") && !finalLogLower.contains("keyboardinterrupt");
                    boolean hasSuccessIndicators = finalLogLower.contains("results saved") ||
                            finalLogLower.contains("speed:") ||
                            finalLogLower.contains("map50") ||
                            finalLogLower.contains("optimizer stripped") ||
                            finalLogLower.contains("training completed") ||
                            finalLogLower.contains("validating");

                    boolean trainSuccess = hasSuccessIndicators && !hasFatalError;

                    String actualPhase = k8sClientService.getPodPhase(finalPodName);

                    if (trainSuccess || "Succeeded".equals(actualPhase)) {
                        String finalLogStr = finalLog + "\nTraining completed successfully\n";
                        updateStatus(jobId, "COMPLETED", 100, finalLogStr, finalPodName);
                        persistTrainLog(recordName, finalLogStr);
                        setTrainFinalStatus(recordName, "COMPLETED", finalPodName, 100);
                    } else {
                        String finalLogStr = finalLog + "\nTraining failed\n";
                        updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                        persistTrainLog(recordName, finalLogStr);
                        setTrainFinalStatus(recordName, "FAILED", finalPodName);
                    }
                } else {
                    String msg = "Failed to find pod for job: " + trainJobName;
                    updateStatus(jobId, "FAILED", 0, msg);
                    setTrainFinalStatus(recordName, "FAILED", null);
                }
            } catch (Exception e) {
                log.error("Training exception for {}: {}", recordName, e.getMessage(), e);
                String actualPhase = (podName != null) ? k8sClientService.getPodPhase(podName) : null;
                if ("Succeeded".equals(actualPhase)) {
                    String fullLog = k8sClientService.getPodLogs(podName);
                    String finalLogStr = (fullLog != null ? fullLog : "") + "\nTraining completed despite exception (Pod Succeeded)\n";
                    updateStatus(jobId, "COMPLETED", 100, finalLogStr, podName);
                    persistTrainLog(recordName, finalLogStr);
                    setTrainFinalStatus(recordName, "COMPLETED", podName, 100);
                } else {
                    updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
                    setTrainFinalStatus(recordName, "FAILED", podName);
                }
            }
        });

        return jobId;
    }

    public String startTesting(String dataName, int imgsz, String recordName, String targetNode) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
            r.setTestJobId(jobId);
            r.setTestStatus("RUNNING");
            trainingRecordRepository.save(r);
        });

        executorService.submit(() -> {
            String podName = null;
            try {
                String testJobName = K8sClientService.sanitizeK8sName(recordName + "-test-job");
                k8sClientService.deleteJob(testJobName);
                Thread.sleep(1000);

                String effectivePvc = usePvc ? pvcName : null;
                V1Job job = k8sClientService.buildTestJob(
                        recordName + "-test-job", dataName, recordName,
                        imgsz, trainingImage,
                        effectivePvc, mountPath,
                        targetNode, null
                );
                k8sClientService.createJob(job);
                log.info("Test job created: {}", testJobName);

                Thread.sleep(2000);

                for (int i = 0; i < 30; i++) {
                    Thread.sleep(1000);
                    podName = k8sClientService.getFirstPodNameByJob(testJobName);
                    if (podName != null) {
                        final String finalPodName = podName;
                        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                            r.setTestPodName(finalPodName);
                            trainingRecordRepository.save(r);
                        });
                        break;
                    }
                }

                if (podName != null) {
                    final String finalPodName = podName;
                    int progress = 0;

                    while (true) {
                        try {
                            Thread.sleep(2000);
                            if (progress < 90) progress += 5;
                            updateStatus(jobId, "RUNNING", progress, "", finalPodName);

                            String phase = k8sClientService.getPodPhase(finalPodName);
                            if ("Succeeded".equals(phase) || "Failed".equals(phase) || "NOT_FOUND".equals(phase)) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    String fullLog = k8sClientService.getPodLogs(finalPodName);
                    String finalLog = fullLog != null ? fullLog : "";
                    String finalLogLower = finalLog.toLowerCase();

                    boolean hasFatalError = finalLogLower.contains("fatal error") && !finalLogLower.contains("keyboardinterrupt");
                    boolean hasSuccessIndicators = finalLogLower.contains("map") ||
                            finalLogLower.contains("precision") ||
                            finalLogLower.contains("recall") ||
                            finalLogLower.contains("speed:") ||
                            finalLogLower.contains("results saved") ||
                            finalLogLower.contains("inference");

                    boolean testSuccess = hasSuccessIndicators && !hasFatalError;
                    String actualPhase = k8sClientService.getPodPhase(finalPodName);

                    if (testSuccess || "Succeeded".equals(actualPhase)) {
                        String finalLogStr = finalLog + "\nTesting completed successfully\n";
                        updateStatus(jobId, "COMPLETED", 100, finalLogStr, finalPodName);
                        persistTestLog(recordName, finalLogStr);
                        setTestFinalStatus(recordName, "COMPLETED", finalPodName);
                    } else {
                        String finalLogStr = finalLog + "\nTesting failed\n";
                        updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                        persistTestLog(recordName, finalLogStr);
                        setTestFinalStatus(recordName, "FAILED", finalPodName);
                    }
                } else {
                    String msg = "Failed to find pod for test job: " + testJobName;
                    updateStatus(jobId, "FAILED", 0, msg);
                    setTestFinalStatus(recordName, "FAILED", null);
                }
            } catch (Exception e) {
                log.error("Testing exception for {}: {}", recordName, e.getMessage(), e);
                String actualPhase = (podName != null) ? k8sClientService.getPodPhase(podName) : null;
                if ("Succeeded".equals(actualPhase)) {
                    String fullLog = k8sClientService.getPodLogs(podName);
                    String finalLogStr = (fullLog != null ? fullLog : "") + "\nTesting completed despite exception (Pod Succeeded)\n";
                    updateStatus(jobId, "COMPLETED", 100, finalLogStr, podName);
                    persistTestLog(recordName, finalLogStr);
                    setTestFinalStatus(recordName, "COMPLETED", podName);
                } else {
                    updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
                    setTestFinalStatus(recordName, "FAILED", podName);
                }
            }
        });

        return jobId;
    }

    public String startTrainingOnNode(String dataName, int epochs, int imgsz, String recordName,
                                       String nodeName, Map<String, String> nodeSelector,
                                       Map<String, String> gpuResources) {
        String effectiveRecordName = (recordName != null && !recordName.isEmpty())
                ? recordName : dataName + "-e" + epochs + "-i" + imgsz;
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        TrainingRecord record = trainingRecordRepository.findByRecordName(effectiveRecordName).orElse(null);
        if (record == null) {
            record = new TrainingRecord(dataName, epochs, imgsz, "system");
        }
        record.setTrainJobId(jobId);
        record.setTrainStatus("RUNNING");
        record.setTrainProgress(0);
        trainingRecordRepository.save(record);

        executorService.submit(() -> {
            String podName = null;
            try {
                String trainJobName = K8sClientService.sanitizeK8sName(effectiveRecordName + "-train-job");
                k8sClientService.deleteJob(trainJobName);
                Thread.sleep(1000);

                String effectivePvc = usePvc ? pvcName : null;
                V1Job job = k8sClientService.buildTrainingJob(
                        effectiveRecordName + "-train-job", dataName, effectiveRecordName,
                        epochs, imgsz, trainingImage,
                        effectivePvc, mountPath,
                        nodeName, nodeSelector, gpuResources
                );
                k8sClientService.createJob(job);
                log.info("Distributed training job created: {} on node {}", trainJobName, nodeName);

                for (int i = 0; i < 30; i++) {
                    Thread.sleep(1000);
                    podName = k8sClientService.getFirstPodNameByJob(trainJobName);
                    if (podName != null) {
                        final String finalPodName = podName;
                        trainingRecordRepository.findByRecordName(effectiveRecordName).ifPresent(r -> {
                            r.setTrainPodName(finalPodName);
                            trainingRecordRepository.save(r);
                        });
                        break;
                    }
                }

                if (podName != null) {
                    final String finalPodName = podName;
                    int progress = 0;

                    while (true) {
                        try {
                            Thread.sleep(2000);
                            String tailLog = k8sClientService.getPodLogs(finalPodName, 10);
                            int parsedProgress = parseEpochProgress(tailLog);
                            if (parsedProgress > 0) {
                                progress = parsedProgress;
                                final int currentProgress = progress;
                                trainingRecordRepository.findByRecordName(effectiveRecordName).ifPresent(r -> {
                                    r.setTrainProgress(currentProgress);
                                    trainingRecordRepository.save(r);
                                });
                            }
                            updateStatus(jobId, "RUNNING", progress, "", finalPodName);

                            String phase = k8sClientService.getPodPhase(finalPodName);
                            if ("Succeeded".equals(phase) || "Failed".equals(phase) || "NOT_FOUND".equals(phase)) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    String fullLog = k8sClientService.getPodLogs(finalPodName);
                    String finalLog = fullLog != null ? fullLog : "";
                    String finalLogLower = finalLog.toLowerCase();
                    boolean hasFatalError = finalLogLower.contains("fatal error") && !finalLogLower.contains("keyboardinterrupt");
                    boolean hasSuccessIndicators = finalLogLower.contains("results saved") ||
                            finalLogLower.contains("speed:") || finalLogLower.contains("map50") ||
                            finalLogLower.contains("optimizer stripped") || finalLogLower.contains("validating");
                    boolean trainSuccess = hasSuccessIndicators && !hasFatalError;
                    String actualPhase = k8sClientService.getPodPhase(finalPodName);

                    if (trainSuccess || "Succeeded".equals(actualPhase)) {
                        String finalLogStr = finalLog + "\nTraining completed successfully\n";
                        updateStatus(jobId, "COMPLETED", 100, finalLogStr, finalPodName);
                        persistTrainLog(effectiveRecordName, finalLogStr);
                        setTrainFinalStatus(effectiveRecordName, "COMPLETED", finalPodName, 100);
                    } else {
                        String finalLogStr = finalLog + "\nTraining failed\n";
                        updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                        persistTrainLog(effectiveRecordName, finalLogStr);
                        setTrainFinalStatus(effectiveRecordName, "FAILED", finalPodName);
                    }
                } else {
                    updateStatus(jobId, "FAILED", 0, "Failed to find pod");
                    setTrainFinalStatus(effectiveRecordName, "FAILED", null);
                }
            } catch (Exception e) {
                log.error("Distributed training exception for {}: {}", effectiveRecordName, e.getMessage(), e);
                updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
                setTrainFinalStatus(effectiveRecordName, "FAILED", podName);
            }
        });

        return jobId;
    }

    public String getPodLogs(String podName) {
        if (k8sClientService.isReady()) {
            return k8sClientService.getPodLogs(podName);
        }
        return "K8s client not ready";
    }

    public String getJobLogs(String jobName) {
        if (k8sClientService.isReady()) {
            String podName = k8sClientService.getFirstPodNameByJob(jobName);
            if (podName != null) {
                return k8sClientService.getPodLogs(podName);
            }
        }
        return "Error: Could not retrieve job logs";
    }

    public String listPods() {
        if (!k8sClientService.isReady()) return "K8s client not ready";
        try {
            var nodes = k8sClientService.getNodeInfoList();
            StringBuilder sb = new StringBuilder();
            sb.append("NAME\t\tSTATUS\t\tROLES\t\tIP\n");
            for (var node : nodes) {
                sb.append(node.get("name")).append("\t\t")
                        .append(node.get("ready")).append("\t\t")
                        .append(node.get("roles")).append("\t\t")
                        .append(node.get("ip")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public JobStatus getJobStatus(String jobId) {
        return jobStatusMap.get(jobId);
    }

    public void removeJobStatus(String jobId) {
        if (jobId != null) {
            jobStatusMap.remove(jobId);
        }
    }

    private void setTrainFinalStatus(String recordName, String status, String podName) {
        setTrainFinalStatus(recordName, status, podName, "COMPLETED".equals(status) ? 100 : 0);
    }

    private void setTrainFinalStatus(String recordName, String status, String podName, int progress) {
        try {
            trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                r.setTrainStatus(status);
                r.setTrainProgress(progress);
                if (podName != null) {
                    r.setTrainPodName(podName);
                }
                trainingRecordRepository.save(r);
            });
            persistLogByType(recordName, "train");
        } catch (Exception e) {
            log.error("Failed to set train final status: {} -> {}", recordName, status, e);
        }
    }

    private void setTestFinalStatus(String recordName, String status, String podName) {
        try {
            trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                r.setTestStatus(status);
                if (podName != null) {
                    r.setTestPodName(podName);
                }
                trainingRecordRepository.save(r);
            });
            persistLogByType(recordName, "test");
        } catch (Exception e) {
            log.error("Failed to set test final status: {} -> {}", recordName, status, e);
        }
    }

    public Map<String, JobStatus> getJobStatusMap() {
        return jobStatusMap;
    }

    public String deleteDataset(String dataName) {
        StringBuilder logBuilder = new StringBuilder();
        try {
            List<TrainingRecord> records = trainingRecordRepository.findByDataName(dataName);
            List<String> recordNames = new ArrayList<>();
            for (TrainingRecord rec : records) {
                if (rec.getRecordName() != null && !rec.getRecordName().isBlank()) {
                    recordNames.add(rec.getRecordName());
                    if (rec.getTrainJobId() != null) jobStatusMap.remove(rec.getTrainJobId());
                    if (rec.getTestJobId() != null) jobStatusMap.remove(rec.getTestJobId());
                }
            }

            for (String recName : recordNames) {
                try {
                    trainingSchedulerService.cancelTask(recName);
                } catch (Exception e) {
                    log.debug("Cancel task notification (ignorable): {} - {}", recName, e.getMessage());
                }
            }

            transactionTemplate.executeWithoutResult(status -> {
                trainingRecordRepository.deleteByDataName(dataName);
                datasetRepository.findByName(dataName).ifPresent(datasetRepository::delete);
            });
            logBuilder.append("Database deleted: dataset ").append(dataName).append(" and related training_records\n");

            for (String recName : recordNames) {
                bestEffortDeleteRecordAssets(recName);
                logBuilder.append("Cleaned up training task resources: ").append(recName).append("\n");
            }

            try {
                k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(dataName + "-preprocess-job"));
                k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(dataName + "-preprocess-job"));
                logBuilder.append("Cleaned up preprocess K8s resources\n");
            } catch (Exception e) {
                log.debug("Preprocess K8s cleanup: {}", e.getMessage());
            }

            String processedDir = PROJECT_ROOT + dataName + "_processed";
            try {
                deleteDirectory(new File(processedDir));
                logBuilder.append("Deleted preprocessed directory: ").append(processedDir).append("\n");
            } catch (Exception e) {
                logBuilder.append("Failed to delete preprocessed directory (manual delete): ").append(e.getMessage()).append("\n");
            }

            String rawDir = PROJECT_ROOT + dataName;
            try {
                deleteDirectory(new File(rawDir));
                logBuilder.append("Deleted raw data directory: ").append(rawDir).append("\n");
            } catch (Exception e) {
                logBuilder.append("Failed to delete raw data directory (manual delete): ").append(e.getMessage()).append("\n");
            }

            return logBuilder.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public boolean deleteTrainingRecord(String recordName) {
        if (recordName == null || recordName.isBlank()) return false;
        if (!trainingRecordRepository.existsByRecordName(recordName)) return false;

        trainingRecordRepository.findByRecordName(recordName).ifPresent(record -> {
            if (record.getTrainJobId() != null) jobStatusMap.remove(record.getTrainJobId());
            if (record.getTestJobId() != null) jobStatusMap.remove(record.getTestJobId());
        });

        try {
            trainingSchedulerService.cancelTask(recordName);
        } catch (Exception e) {
            log.debug("Cancel task notification (ignorable): {}", e.getMessage());
        }

        try {
            transactionTemplate.executeWithoutResult(status -> trainingRecordRepository.deleteByRecordName(recordName));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete training record: " + e.getMessage(), e);
        }
        bestEffortDeleteRecordAssets(recordName);
        return true;
    }

    private void bestEffortDeleteRecordAssets(String recordName) {
        try {
            k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(recordName + "-train-job"));
            k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(recordName + "-test-job"));
            k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(recordName + "-train-job"));
            k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(recordName + "-test-job"));
        } catch (Exception e) {
            log.debug("K8s cleanup exception (ignorable): {}", e.getMessage());
        }
        try {
            deleteDirectory(new File(PROJECT_ROOT + "runs/detect/" + recordName + "_train"));
            deleteDirectory(new File(PROJECT_ROOT + "runs/detect/" + recordName + "_train_test"));
        } catch (Exception e) {
            log.debug("Runs directory cleanup: {}", e.getMessage());
        }
        try {
            new File(LOGS_DIR, recordName + "-train.txt").delete();
            new File(LOGS_DIR, recordName + "-test.txt").delete();
        } catch (Exception ignored) {
        }
    }

    public List<DatasetStatus> getDatasetStatusList() {
        List<DatasetStatus> result = new ArrayList<>();
        try {
            List<Dataset> datasets = datasetRepository.findAll();
            for (Dataset dataset : datasets) {
                DatasetStatus ds = new DatasetStatus(dataset.getName());
                ds.setInputPath(dataset.getInputPath());
                ds.setPreprocessed(dataset.getPreprocessed());
                ds.setCreatedBy(dataset.getCreatedBy());
                result.add(ds);
            }
        } catch (Exception e) {
            log.error("Failed to get dataset status list", e);
        }
        return result;
    }

    public String saveModel(String dataName, String modelType, String savePath, String recordName) {
        try {
            String trainDirName = (recordName != null && !recordName.isEmpty()) ? recordName + "_train" : dataName + "_processed_train";
            String sourceDir = PROJECT_ROOT + "runs/detect/" + trainDirName + "/weights";

            File sourceDirectory = new File(sourceDir);
            if (!sourceDirectory.exists()) {
                sourceDir = PROJECT_ROOT + "runs/detect/" + dataName + "_processed_train/weights";
                sourceDirectory = new File(sourceDir);
                if (!sourceDirectory.exists()) {
                    return "Error: model directory does not exist: " + sourceDir;
                }
            }

            String sourceFileName = modelType.equalsIgnoreCase("best") ? "best.pt" : "last.pt";
            File sourceFile = new File(sourceDir, sourceFileName);

            if (!sourceFile.exists()) {
                return "Error: model file does not exist: " + sourceFile.getPath();
            }

            if (savePath == null || savePath.trim().isEmpty()) {
                savePath = PROJECT_ROOT + "saved_models";
            }

            File destDirectory = new File(savePath);
            if (!destDirectory.exists()) {
                destDirectory.mkdirs();
            }

            String destFileName = (recordName != null && !recordName.isEmpty()) ? recordName + "_" + modelType + ".pt" : dataName + "_" + modelType + ".pt";
            File destFile = new File(destDirectory, destFileName);

            Files.copy(sourceFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return destFile.getAbsolutePath();
        } catch (Exception e) {
            return "Save failed: " + e.getMessage();
        }
    }

    public List<String> getSavedModels() {
        List<String> models = new ArrayList<>();
        String destDir = PROJECT_ROOT + "saved_models";
        File destDirectory = new File(destDir);

        if (destDirectory.exists() && destDirectory.isDirectory()) {
            File[] files = destDirectory.listFiles((dir, name) -> name.endsWith(".pt"));
            if (files != null) {
                for (File file : files) {
                    models.add(file.getName());
                }
            }
        }
        return models;
    }

    public Map<String, Object> cleanupAll() {
        Map<String, Object> result = new HashMap<>();
        StringBuilder logBuilder = new StringBuilder();

        try {
            List<Dataset> allDatasets = datasetRepository.findAll();
            for (Dataset ds : allDatasets) {
                logBuilder.append(deleteDataset(ds.getName())).append("\n");
            }

            List<TrainingRecord> allRecords = trainingRecordRepository.findAll();
            for (TrainingRecord rec : allRecords) {
                String recName = rec.getRecordName();
                if (recName != null && !recName.isEmpty()) {
                    k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(recName + "-train-job"));
                    k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(recName + "-test-job"));
                    k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(recName + "-train-job"));
                    k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(recName + "-test-job"));
                }
            }
            trainingRecordRepository.deleteAll();

            for (Dataset ds : allDatasets) {
                try {
                    k8sClientService.deleteJob(K8sClientService.sanitizeK8sName(ds.getName() + "-preprocess-job"));
                    k8sClientService.deletePodsByJob(K8sClientService.sanitizeK8sName(ds.getName() + "-preprocess-job"));
                } catch (Exception ignored) {}
                try {
                    deleteDirectory(new File(PROJECT_ROOT + ds.getName() + "_processed"));
                    deleteDirectory(new File(PROJECT_ROOT + ds.getName()));
                } catch (Exception ignored) {}
            }

            File runsDir = new File(PROJECT_ROOT + "runs/detect");
            if (runsDir.exists()) {
                deleteDirectory(runsDir);
            }

            File logsDir = new File(LOGS_DIR);
            if (logsDir.exists()) {
                deleteDirectory(logsDir);
                logsDir.mkdirs();
            }

            jobStatusMap.clear();

            result.put("deletedDatasets", allDatasets.size());
            result.put("deletedRecords", allRecords.size());
            result.put("log", logBuilder.toString());
            result.put("status", "success");
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("status", "error");
        }

        return result;
    }

    private void deleteDirectory(File dir) {
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }

    public void persistLogByType(String recordName, String type) {
        try {
            Path logPath = Paths.get(LOGS_DIR, recordName + "-" + type + ".txt");
            if (Files.exists(logPath)) return;

            TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
            if (record == null) return;

            String podName = "train".equals(type) ? record.getTrainPodName() : record.getTestPodName();
            String logContent = null;

            if (podName != null && !podName.isEmpty()) {
                logContent = getPodLogs(podName);
                if (logContent != null && logContent.startsWith("Error")) logContent = null;
            }

            if (logContent == null || logContent.isEmpty()) {
                String jobName = K8sClientService.sanitizeK8sName(recordName + "-" + type + "-job");
                logContent = getJobLogs(jobName);
                if (logContent != null && logContent.startsWith("Error")) logContent = null;
            }

            if (logContent != null && !logContent.isEmpty()) {
                File dir = new File(LOGS_DIR);
                if (!dir.exists()) dir.mkdirs();
                Files.writeString(logPath, logContent, StandardCharsets.UTF_8);
                if ("train".equals(type)) {
                    record.setTrainLogFile(logPath.getFileName().toString());
                } else {
                    record.setTestLogFile(logPath.getFileName().toString());
                }
                trainingRecordRepository.save(record);
                log.info("Saved {} log for {} to {}", type, recordName, logPath.getFileName());
            }
        } catch (Exception e) {
            log.warn("Failed to save {} log for {}: {}", type, recordName, e.getMessage());
        }
    }

    public void saveLogToFile(String logName, String content) {
        try {
            File dir = new File(LOGS_DIR);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, logName + ".txt");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
            log.info("Log saved: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to save log: {} - {}", logName, e.getMessage());
        }
    }

    private void persistTrainLog(String recordName, String content) {
        String fileBase = recordName + "-train";
        saveLogToFile(fileBase, content);
        String fileName = fileBase + ".txt";
        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
            r.setTrainLogFile(fileName);
            trainingRecordRepository.save(r);
        });
    }

    private void persistTestLog(String recordName, String content) {
        String fileBase = recordName + "-test";
        saveLogToFile(fileBase, content);
        String fileName = fileBase + ".txt";
        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
            r.setTestLogFile(fileName);
            trainingRecordRepository.save(r);
        });
    }

    public String readLogContentByFileName(String logFileName) {
        if (logFileName == null || logFileName.isBlank()) return null;
        try {
            File file = new File(LOGS_DIR, logFileName.trim());
            if (!file.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public String readLogFromFile(String logName) {
        try {
            File file = new File(LOGS_DIR, logName + ".txt");
            if (!file.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private int parseEpochProgress(String logContent) {
        if (logContent == null || logContent.isEmpty()) return 0;
        try {
            Matcher m = Pattern.compile("(\\d+)/(\\d+)\\s+\\d+G", Pattern.CASE_INSENSITIVE).matcher(logContent);
            int maxEpoch = 0, totalEpochs = 0;
            while (m.find()) {
                int current = Integer.parseInt(m.group(1));
                int total = Integer.parseInt(m.group(2));
                if (total > 0 && current <= total) {
                    maxEpoch = current;
                    totalEpochs = total;
                }
            }
            if (totalEpochs > 0) {
                return Math.min((maxEpoch * 100) / totalEpochs, 100);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void updateStatus(String jobId, String status, int progress, String log) {
        JobStatus jobStatus = jobStatusMap.get(jobId);
        if (jobStatus != null) {
            jobStatus.setStatus(status);
            jobStatus.setProgress(progress);
            jobStatus.setLog(log);
        }
    }

    private void updateStatus(String jobId, String status, int progress, String log, String podName) {
        JobStatus jobStatus = jobStatusMap.get(jobId);
        if (jobStatus != null) {
            jobStatus.setStatus(status);
            jobStatus.setProgress(progress);
            jobStatus.setLog(log);
            jobStatus.setPodName(podName);
        }
    }
}
