package com.example.yoloproject.service;

import com.example.yoloproject.dto.DatasetStatus;
import com.example.yoloproject.dto.JobStatus;
import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.DatasetRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class YoloService {

    private final Map<String, JobStatus> jobStatusMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final String PROJECT_ROOT;
    private static final String PREPROCESS_SCRIPT;
    private static final String GENERATE_YAML_SCRIPT;
    private static final String LOGS_DIR;

    static {
        String path = System.getProperty("user.dir").replace("/", "\\");
        PROJECT_ROOT = new java.io.File(path).getParentFile().getAbsolutePath().replace("/", "\\");
        PREPROCESS_SCRIPT = PROJECT_ROOT + "\\preprocess.py";
        GENERATE_YAML_SCRIPT = PROJECT_ROOT + "\\generate_k8s_job_yaml.py";
        LOGS_DIR = PROJECT_ROOT + "\\logs";
    }

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    public String getProjectRoot() {
        return PROJECT_ROOT;
    }

    public String startPreprocess(String inputDir) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        executorService.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python", PREPROCESS_SCRIPT, "--input_dir", inputDir);
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                StringBuilder logBuilder = new StringBuilder();
                String line;

                logBuilder.append("Running: python preprocess.py --input_dir ").append(inputDir).append("\n");
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
                logBuilder.append("Error: " + e.getMessage() + "\n");
                logBuilder.append("Auto-deleting dataset...\n");
                updateStatus(jobId, "FAILED", 0, logBuilder.toString());

                String dataName = new File(inputDir).getName();
                deleteDataset(dataName);
            }
        });

        return jobId;
    }

    public String startTraining(String dataName, int epochs, int imgsz, String explicitRecordName) {
        String recordName = (explicitRecordName != null && !explicitRecordName.isEmpty()) ? explicitRecordName : dataName + "-e" + epochs + "-i" + imgsz;
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
            try {
                String yamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + recordName + "-train.yaml";

                deleteK8sJob(recordName + "-train-job");

                regenerateTrainYaml(dataName, epochs, imgsz, recordName);

                File yamlFile = new File(yamlPath);
                StringBuilder logBuilder = new StringBuilder();

                if (!yamlFile.exists()) {
                    logBuilder.append("错误: YAML文件不存在: ").append(yamlPath).append("\n");
                    logBuilder.append("请先执行预处理操作生成配置文件\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                    trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                        r.setTrainStatus("FAILED");
                        trainingRecordRepository.save(r);
                    });
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder("kubectl", "apply", "-f", yamlPath);
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                BufferedReader processReader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String processLine;
                StringBuilder processOutput = new StringBuilder();
                while ((processLine = processReader.readLine()) != null) {
                    processOutput.append(processLine).append("\n");
                }
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    logBuilder.append("训练状态: Running\n");
                    logBuilder.append("Pod创建中...\n");
                    logBuilder.append("kubectl输出: ").append(processOutput);
                    updateStatus(jobId, "RUNNING", 0, logBuilder.toString());

                    String podName = null;
                    for (int i = 0; i < 20; i++) {
                        try {
                            Thread.sleep(500);
                            Process podProcess = new ProcessBuilder("kubectl", "get", "pods", "-l", "job-name=" + recordName + "-train-job", "-o", "name").start();
                            BufferedReader podReader = new BufferedReader(new InputStreamReader(podProcess.getInputStream(), "UTF-8"));
                            String podLine = podReader.readLine();
                            if (podLine != null && podLine.startsWith("pod/")) {
                                podName = podLine.substring(4);
                                logBuilder.append("Pod创建成功: ").append(podName).append("\n");
                                logBuilder.append("开始获取实时日志...\n");
                                updateStatus(jobId, "RUNNING", 0, logBuilder.toString(), podName);

                                final String finalPodName = podName;
                                trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                    r.setTrainPodName(finalPodName);
                                    trainingRecordRepository.save(r);
                                });
                                break;
                            }
                        } catch (Exception e) {
                        }
                    }

                    if (podName != null) {
                        final String finalPodName = podName;
                        int progress = 0;
                        int trainLogSnapshotTick = 0;

                        while (true) {
                            try {
                                Thread.sleep(1000);

                                Process logProcess = new ProcessBuilder("kubectl", "logs", finalPodName).start();
                                BufferedReader logReader = new BufferedReader(new InputStreamReader(logProcess.getInputStream(), "UTF-8"));
                                StringBuilder currentLogs = new StringBuilder();
                                String line;
                                while ((line = logReader.readLine()) != null) {
                                    currentLogs.append(line).append("\n");
                                }
                                logProcess.waitFor();

                                String fullLog = "训练状态: Running\nPod创建成功: " + finalPodName + "\n开始获取实时日志...\n" + currentLogs.toString();

                                String logStr = currentLogs.toString();
                                int parsedProgress = parseEpochProgress(logStr);
                                if (parsedProgress > 0) {
                                    progress = parsedProgress;
                                    final int currentProgress = progress;
                                    trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                        r.setTrainProgress(currentProgress);
                                        trainingRecordRepository.save(r);
                                    });
                                }

                                updateStatus(jobId, "RUNNING", progress, fullLog, finalPodName);

                                if (++trainLogSnapshotTick % 30 == 0) {
                                    persistTrainLog(recordName, fullLog);
                                }

                                Process checkProcess = new ProcessBuilder("kubectl", "get", "pod", finalPodName, "-o", "jsonpath={.status.phase}").start();
                                BufferedReader checkReader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream(), "UTF-8"));
                                String phase = checkReader.readLine();
                                checkProcess.waitFor();

                                if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                                    break;
                                }
                            } catch (Exception e) {
                            }
                        }

                        String finalLog = jobStatusMap.get(jobId) != null ? jobStatusMap.get(jobId).getLog() : "";
                        String finalLogLower = finalLog.toLowerCase();

                        boolean hasFatalError = finalLogLower.contains("fatal error") || finalLogLower.contains("traceback");
                        boolean hasSuccessIndicators = finalLogLower.contains("results saved") ||
                            finalLogLower.contains("speed:") ||
                            finalLogLower.contains("map50") ||
                            finalLogLower.contains("optimizer stripped") ||
                            finalLogLower.contains("training completed") ||
                            finalLogLower.contains("validating");

                        boolean trainSuccess = hasSuccessIndicators && !hasFatalError;

                        if (trainSuccess) {
                            StringBuilder successLog = new StringBuilder(finalLog);
                            successLog.append("\nTraining completed successfully\n");
                            String finalLogStr = successLog.toString();
                            updateStatus(jobId, "COMPLETED", 100, finalLogStr, finalPodName);
                            persistTrainLog(recordName, finalLogStr);
                            trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                r.setTrainStatus("COMPLETED");
                                r.setTrainProgress(100);
                                r.setTrainPodName(finalPodName);
                                trainingRecordRepository.save(r);
                            });
                        } else {
                            StringBuilder failLog = new StringBuilder(finalLog);
                            failLog.append("\nTraining failed\n");
                            String finalLogStr = failLog.toString();
                            updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                            persistTrainLog(recordName, finalLogStr);
                            trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                r.setTrainStatus("FAILED");
                                trainingRecordRepository.save(r);
                            });
                        }
                    } else {
                        logBuilder.append("Failed to find pod\n");
                        updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                            r.setTrainStatus("FAILED");
                            trainingRecordRepository.save(r);
                        });
                    }
                } else {
                    logBuilder.append("Training job submission failed\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                    trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                        r.setTrainStatus("FAILED");
                        trainingRecordRepository.save(r);
                    });
                }
            } catch (Exception e) {
                updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
                trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                    r.setTrainStatus("FAILED");
                    trainingRecordRepository.save(r);
                });
            }
        });

        return jobId;
    }

    public String startTesting(String dataName, int imgsz, String recordName) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
            r.setTestJobId(jobId);
            r.setTestStatus("RUNNING");
            trainingRecordRepository.save(r);
        });

        executorService.submit(() -> {
            try {
                String yamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + recordName + "-test.yaml";

                deleteK8sJob(recordName + "-test-job");

                regenerateTestYaml(dataName, imgsz, recordName);

                File yamlFile = new File(yamlPath);
                StringBuilder logBuilder = new StringBuilder();

                if (!yamlFile.exists()) {
                    logBuilder.append("错误: YAML文件不存在: ").append(yamlPath).append("\n");
                    logBuilder.append("请先执行训练操作生成配置文件\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                    trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                        r.setTestStatus("FAILED");
                        trainingRecordRepository.save(r);
                    });
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder("kubectl", "apply", "-f", yamlPath);
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                BufferedReader processReader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String processLine;
                StringBuilder processOutput = new StringBuilder();
                while ((processLine = processReader.readLine()) != null) {
                    processOutput.append(processLine).append("\n");
                }
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    logBuilder.append("测试状态: Running\n");
                    logBuilder.append("正在等待节点创建...\n");
                    updateStatus(jobId, "RUNNING", 0, logBuilder.toString());

                    Thread.sleep(2000);

                    String podName = null;
                    for (int i = 0; i < 30; i++) {
                        try {
                            Thread.sleep(1000);
                            Process podProcess = new ProcessBuilder("kubectl", "get", "pods", "-l", "job-name=" + recordName + "-test-job", "-o", "name").start();
                            BufferedReader podReader = new BufferedReader(new InputStreamReader(podProcess.getInputStream(), "UTF-8"));
                            String podLine = podReader.readLine();
                            if (podLine != null && podLine.startsWith("pod/")) {
                                podName = podLine.substring(4);
                                logBuilder.append("Pod创建成功: ").append(podName).append("\n");
                                logBuilder.append("开始获取实时日志...\n");
                                updateStatus(jobId, "RUNNING", 0, logBuilder.toString(), podName);

                                final String finalPodName = podName;
                                trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                    r.setTestPodName(finalPodName);
                                    trainingRecordRepository.save(r);
                                });
                                break;
                            }
                        } catch (Exception e) {
                        }
                    }

                    if (podName != null) {
                        final String finalPodName = podName;
                        int progress = 0;
                        int testLogSnapshotTick = 0;

                        while (true) {
                            try {
                                Thread.sleep(1000);

                                Process logProcess = new ProcessBuilder("kubectl", "logs", finalPodName).start();
                                BufferedReader logReader = new BufferedReader(new InputStreamReader(logProcess.getInputStream(), "UTF-8"));
                                StringBuilder currentLogs = new StringBuilder();
                                String line;
                                while ((line = logReader.readLine()) != null) {
                                    currentLogs.append(line).append("\n");
                                }
                                logProcess.waitFor();

                                String fullLog = "测试状态: Running\nPod创建成功: " + finalPodName + "\n开始获取实时日志...\n" + currentLogs.toString();

                                if (progress < 90) {
                                    progress += 5;
                                }

                                updateStatus(jobId, "RUNNING", progress, fullLog, finalPodName);

                                if (++testLogSnapshotTick % 30 == 0) {
                                    persistTestLog(recordName, fullLog);
                                }

                                Process checkProcess = new ProcessBuilder("kubectl", "get", "pod", finalPodName, "-o", "jsonpath={.status.phase}").start();
                                BufferedReader checkReader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream(), "UTF-8"));
                                String phase = checkReader.readLine();
                                checkProcess.waitFor();

                                if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                                    break;
                                }
                            } catch (Exception e) {
                            }
                        }

                        String finalLog = jobStatusMap.get(jobId) != null ? jobStatusMap.get(jobId).getLog() : "";
                        String finalLogLower = finalLog.toLowerCase();

                        boolean hasFatalError = finalLogLower.contains("fatal error") || finalLogLower.contains("traceback");
                        boolean hasSuccessIndicators = finalLogLower.contains("map") ||
                            finalLogLower.contains("precision") ||
                            finalLogLower.contains("recall") ||
                            finalLogLower.contains("speed:") ||
                            finalLogLower.contains("results saved") ||
                            finalLogLower.contains("inference");

                        boolean testSuccess = hasSuccessIndicators && !hasFatalError;

                        if (testSuccess) {
                            StringBuilder successLog = new StringBuilder(finalLog);
                            successLog.append("\nTesting completed successfully\n");
                            String finalLogStr = successLog.toString();
                            updateStatus(jobId, "COMPLETED", 100, finalLogStr, finalPodName);
                            persistTestLog(recordName, finalLogStr);
                            trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                r.setTestStatus("COMPLETED");
                                trainingRecordRepository.save(r);
                            });
                        } else {
                            StringBuilder failLog = new StringBuilder(finalLog);
                            failLog.append("\nTesting failed\n");
                            String finalLogStr = failLog.toString();
                            updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                            persistTestLog(recordName, finalLogStr);
                            trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                                r.setTestStatus("FAILED");
                                trainingRecordRepository.save(r);
                            });
                        }
                    } else {
                        logBuilder.append("Failed to find pod\n");
                        updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                        trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                            r.setTestStatus("FAILED");
                            trainingRecordRepository.save(r);
                        });
                    }
                } else {
                    logBuilder.append("Testing job submission failed\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                    trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                        r.setTestStatus("FAILED");
                        trainingRecordRepository.save(r);
                    });
                }
            } catch (Exception e) {
                updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
                trainingRecordRepository.findByRecordName(recordName).ifPresent(r -> {
                    r.setTestStatus("FAILED");
                    trainingRecordRepository.save(r);
                });
            }
        });

        return jobId;
    }

    public String getPodLogs(String podName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "logs", podName);
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder logBuilder = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                logBuilder.append(line).append("\n");
            }

            process.waitFor();
            return logBuilder.toString();
        } catch (Exception e) {
            return "获取日志中...\n" + e.getMessage();
        }
    }

    public String getJobLogs(String jobName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "logs", "job/" + jobName);
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public String listPods() {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "pods", "-o", "wide");
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public JobStatus getJobStatus(String jobId) {
        return jobStatusMap.get(jobId);
    }

    public Map<String, JobStatus> getJobStatusMap() {
        return jobStatusMap;
    }

    /**
     * 删除数据集完整顺序：<br>
     * 1) 在<strong>短事务</strong>内删除 {@code training_records} 与 {@code datasets}（JPA 派生 delete 必须在事务中执行，否则会失败或不落库）<br>
     * 2) 再尽力删除各训练任务的 K8s / yaml / runs / logs<br>
     * 3) 最后删除 {@code dataName_processed} 预处理目录<br>
     */
    public String deleteDataset(String dataName) {
        StringBuilder logBuilder = new StringBuilder();
        try {
            List<TrainingRecord> records = trainingRecordRepository.findByDataName(dataName);
            List<String> recordNames = new ArrayList<>();
            for (TrainingRecord rec : records) {
                if (rec.getRecordName() != null && !rec.getRecordName().isBlank()) {
                    recordNames.add(rec.getRecordName());
                }
            }

            transactionTemplate.executeWithoutResult(status -> {
                trainingRecordRepository.deleteByDataName(dataName);
                datasetRepository.findByName(dataName).ifPresent(datasetRepository::delete);
            });
            logBuilder.append("数据库已提交删除：数据集 ").append(dataName).append(" 及关联 training_records\n");

            for (String recName : recordNames) {
                bestEffortDeleteRecordAssets(recName);
                logBuilder.append("已尽力清理训练任务资源: ").append(recName).append("\n");
            }

            String processedDir = PROJECT_ROOT + "\\" + dataName + "_processed";
            try {
                deleteDirectory(new File(processedDir));
                logBuilder.append("已删除预处理目录: ").append(processedDir).append("\n");
            } catch (Exception e) {
                logBuilder.append("删除预处理目录失败(可手动删): ").append(e.getMessage()).append("\n");
            }

            return logBuilder.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 先在事务中删除 {@code training_records} 行，再清理本地/K8s；避免无事务时派生 delete 无效。
     */
    public boolean deleteTrainingRecord(String recordName) {
        if (recordName == null || recordName.isBlank()) {
            return false;
        }
        if (!trainingRecordRepository.existsByRecordName(recordName)) {
            return false;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> trainingRecordRepository.deleteByRecordName(recordName));
        } catch (Exception e) {
            throw new IllegalStateException("数据库删除训练记录失败（请确认事务与表映射正常）: " + e.getMessage(), e);
        }
        bestEffortDeleteRecordAssets(recordName);
        return true;
    }

    private void bestEffortDeleteRecordAssets(String recordName) {
        try {
            deleteK8sJob(recordName + "-train-job");
            deleteK8sJob(recordName + "-test-job");
            deleteK8sPodsByJob(recordName + "-train-job");
            deleteK8sPodsByJob(recordName + "-test-job");
        } catch (Exception e) {
            System.out.println("K8s 清理异常(可忽略): " + e.getMessage());
        }
        try {
            new File(PROJECT_ROOT + "\\k8s_jobs\\" + recordName + "-train.yaml").delete();
            new File(PROJECT_ROOT + "\\k8s_jobs\\" + recordName + "-test.yaml").delete();
        } catch (Exception ignored) {
        }
        try {
            deleteDirectory(new File(PROJECT_ROOT + "\\runs\\detect\\" + recordName + "_train"));
            deleteDirectory(new File(PROJECT_ROOT + "\\runs\\detect\\" + recordName + "_train_test"));
        } catch (Exception e) {
            System.out.println("runs 目录清理: " + e.getMessage());
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
        }

        return result;
    }

    public String saveModel(String dataName, String modelType, String savePath, String recordName) {
        try {
            String trainDirName = (recordName != null && !recordName.isEmpty()) ? recordName + "_train" : dataName + "_processed_train";
            String sourceDir = PROJECT_ROOT + "\\runs\\detect\\" + trainDirName + "\\weights";

            File sourceDirectory = new File(sourceDir);
            if (!sourceDirectory.exists()) {
                sourceDir = PROJECT_ROOT + "\\runs\\detect\\" + dataName + "_processed_train\\weights";
                sourceDirectory = new File(sourceDir);
                if (!sourceDirectory.exists()) {
                    return "错误: 模型目录不存在: " + sourceDir;
                }
            }

            String sourceFileName = modelType.equalsIgnoreCase("best") ? "best.pt" : "last.pt";
            File sourceFile = new File(sourceDir, sourceFileName);

            if (!sourceFile.exists()) {
                return "错误: 模型文件不存在: " + sourceFile.getPath();
            }

            if (savePath == null || savePath.trim().isEmpty()) {
                savePath = PROJECT_ROOT + "\\saved_models";
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
            return "保存失败: " + e.getMessage();
        }
    }

    public List<String> getSavedModels() {
        List<String> models = new ArrayList<>();
        String destDir = PROJECT_ROOT + "\\saved_models";
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
                    deleteK8sJob(recName + "-train-job");
                    deleteK8sJob(recName + "-test-job");
                    deleteK8sPodsByJob(recName + "-train-job");
                    deleteK8sPodsByJob(recName + "-test-job");
                    String trainYaml = PROJECT_ROOT + "\\k8s_jobs\\" + recName + "-train.yaml";
                    String testYaml = PROJECT_ROOT + "\\k8s_jobs\\" + recName + "-test.yaml";
                    new File(trainYaml).delete();
                    new File(testYaml).delete();
                }
            }
            trainingRecordRepository.deleteAll();

            File k8sJobsDir = new File(PROJECT_ROOT + "\\k8s_jobs");
            if (k8sJobsDir.exists()) {
                for (File f : k8sJobsDir.listFiles()) { f.delete(); }
            }

            File runsDir = new File(PROJECT_ROOT + "\\runs\\detect");
            if (runsDir.exists()) {
                deleteDirectory(runsDir);
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

    private void deleteK8sJob(String jobName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "delete", "job", jobName, "--ignore-not-found=true");
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            System.out.println("已删除旧Job: " + jobName);
        } catch (Exception e) {
            System.out.println("删除旧Job失败(可忽略): " + jobName + " - " + e.getMessage());
        }
    }

    private void deleteK8sPodsByJob(String jobName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "delete", "pod", "-l", "job-name=" + jobName, "--ignore-not-found=true");
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            System.out.println("已删除关联Pod: job-name=" + jobName);
        } catch (Exception e) {
            System.out.println("删除Pod失败(可忽略): " + jobName + " - " + e.getMessage());
        }
    }

    private void saveLogToFile(String logName, String content) {
        try {
            File dir = new File(LOGS_DIR);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, logName + ".txt");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
            System.out.println("日志已保存: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("保存日志失败: " + logName + " - " + e.getMessage());
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

    /** 按数据库中记载的文件名读取 logs 目录下文本（完成后查看日志） */
    public String readLogContentByFileName(String logFileName) {
        if (logFileName == null || logFileName.isBlank()) {
            return null;
        }
        try {
            File file = new File(LOGS_DIR, logFileName.trim());
            if (!file.isFile()) {
                return null;
            }
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

    private void regenerateTrainYaml(String dataName, int epochs, int imgsz, String recordName) {
        try {
            executeGenerateYamlScript(dataName, "train", epochs, imgsz, recordName);
        } catch (Exception e) {
            System.out.println("重新生成训练YAML失败: " + e.getMessage());
        }
    }

    private void regenerateTestYaml(String dataName, int imgsz, String recordName) {
        try {
            executeGenerateYamlScript(dataName, "test", 0, imgsz, recordName);
        } catch (Exception e) {
            System.out.println("重新生成测试YAML失败: " + e.getMessage());
        }
    }

    private void executeGenerateYamlScript(String dataName, String jobType, int epochs, int imgsz, String recordName) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("python");
        command.add(GENERATE_YAML_SCRIPT);
        command.add("--data_name");
        command.add(dataName);
        command.add("--job_type");
        command.add(jobType);
        command.add("--output_dir");
        command.add(PROJECT_ROOT + "\\k8s_jobs");
        if ("train".equals(jobType)) {
            command.add("--epochs");
            command.add(String.valueOf(epochs));
        }
        command.add("--imgsz");
        command.add(String.valueOf(imgsz));
        if (recordName != null && !recordName.isEmpty()) {
            command.add("--record_name");
            command.add(recordName);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(PROJECT_ROOT));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("生成YAML失败，退出码: " + exitCode);
        }
    }

    private int parseEpochProgress(String log) {
        if (log == null || log.isEmpty()) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)/(\\d+)\\s+\\d+G", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(log);
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
                int progress = (maxEpoch * 100) / totalEpochs;
                return Math.min(progress, 100);
            }
        } catch (Exception e) {
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
