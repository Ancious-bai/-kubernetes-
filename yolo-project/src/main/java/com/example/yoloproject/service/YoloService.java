package com.example.yoloproject.service;

import com.example.yoloproject.dto.DatasetStatus;
import com.example.yoloproject.dto.JobStatus;
import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.repository.DatasetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final String PROJECT_ROOT = "E:\\Ancious\\Desktop\\毕业设计\\Code";
    private static final String PREPROCESS_SCRIPT = PROJECT_ROOT + "\\preprocess.py";
    
    @Autowired
    private DatasetRepository datasetRepository;

    public String getProjectRoot() {
        return PROJECT_ROOT;
    }

    public String startPreprocess(String inputDir, int epochs, int imgsz) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        executorService.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python", PREPROCESS_SCRIPT, "--input_dir", inputDir, "--epochs", String.valueOf(epochs), "--imgsz", String.valueOf(imgsz));
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                StringBuilder logBuilder = new StringBuilder();
                String line;

                logBuilder.append("Running: python preprocess.py --input_dir ").append(inputDir).append(" --epochs ").append(epochs).append(" --imgsz ").append(imgsz).append("\n");
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
                    
                    // 预处理成功，更新数据库状态
                    String dataName = new File(inputDir).getName();
                    datasetRepository.findByName(dataName).ifPresent(d -> {
                        d.setPreprocessed(true);
                        datasetRepository.save(d);
                    });
                } else {
                    logBuilder.append("Command failed with exit code: ").append(exitCode).append("\n");
                    logBuilder.append("Auto-deleting dataset...\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                    
                    // 自动删除失败的数据集
                    String dataName = new File(inputDir).getName();
                    deleteDataset(dataName);
                }
            } catch (Exception e) {
                StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("Error: " + e.getMessage() + "\n");
                logBuilder.append("Auto-deleting dataset...\n");
                updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                
                // 自动删除失败的数据集
                String dataName = new File(inputDir).getName();
                deleteDataset(dataName);
            }
        });

        return jobId;
    }

    public String startTraining(String dataName, int epochs, int imgsz) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        datasetRepository.findByName(dataName).ifPresent(d -> {
            d.setTrainJobId(jobId);
            d.setTrainStatus("RUNNING");
            d.setTrainProgress(0);
            datasetRepository.save(d);
        });

        executorService.submit(() -> {
            try {
                String yamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + dataName + "-train.yaml";
                
                deleteK8sJob(dataName + "-train-job");
                
                regenerateTrainYaml(dataName, epochs, imgsz);
                
                File yamlFile = new File(yamlPath);
                StringBuilder logBuilder = new StringBuilder();
                
                if (!yamlFile.exists()) {
                    logBuilder.append("错误: YAML文件不存在: ").append(yamlPath).append("\n");
                    logBuilder.append("请先执行预处理操作生成配置文件\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
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
                    logBuilder.append("正在等待节点创建...\n");
                    logBuilder.append("kubectl输出: ").append(processOutput);
                    updateStatus(jobId, "RUNNING", 0, logBuilder.toString());
                    
                    Thread.sleep(2000);
                    
                    String podName = null;
                    for (int i = 0; i < 30; i++) {
                        try {
                            Thread.sleep(1000);
                            Process podProcess = new ProcessBuilder("kubectl", "get", "pods", "-l", "job-name=" + dataName + "-train-job", "-o", "name").start();
                            BufferedReader podReader = new BufferedReader(new InputStreamReader(podProcess.getInputStream(), "UTF-8"));
                            String podLine = podReader.readLine();
                            if (podLine != null && podLine.startsWith("pod/")) {
                                podName = podLine.substring(4);
                                logBuilder.append("Pod创建成功: ").append(podName).append("\n");
                                logBuilder.append("开始获取实时日志...\n");
                                updateStatus(jobId, "RUNNING", 0, logBuilder.toString(), podName);
                                
                                final String finalPodName = podName;
                                datasetRepository.findByName(dataName).ifPresent(dataset -> {
                                    dataset.setTrainPodName(finalPodName);
                                    datasetRepository.save(dataset);
                                });
                                break;
                            }
                        } catch (Exception e) {
                            // 忽略错误，继续尝试
                        }
                    }
                    
                    if (podName != null) {
                        final String finalPodName = podName;
                        int progress = 0;
                        
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
                                    datasetRepository.findByName(dataName).ifPresent(d -> {
                                        d.setTrainProgress(currentProgress);
                                        datasetRepository.save(d);
                                    });
                                }
                                
                                updateStatus(jobId, "RUNNING", progress, fullLog, finalPodName);
                                
                                Process checkProcess = new ProcessBuilder("kubectl", "get", "pod", finalPodName, "-o", "jsonpath={.status.phase}").start();
                                BufferedReader checkReader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream(), "UTF-8"));
                                String phase = checkReader.readLine();
                                checkProcess.waitFor();
                                
                                if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                                    break;
                                }
                            } catch (Exception e) {
                                // 忽略单次轮询错误
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
                            datasetRepository.findByName(dataName).ifPresent(d -> {
                                d.setTrainStatus("COMPLETED");
                                d.setTrained(true);
                                d.setTrainProgress(100);
                                d.setTrainPodName(finalPodName);
                                datasetRepository.save(d);
                            });
                        } else {
                            StringBuilder failLog = new StringBuilder(finalLog);
                            failLog.append("\nTraining failed\n");
                            String finalLogStr = failLog.toString();
                            updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                            datasetRepository.findByName(dataName).ifPresent(d -> {
                                d.setTrainStatus("FAILED");
                                d.setTrained(false);
                                datasetRepository.save(d);
                            });
                        }
                    } else {
                        logBuilder.append("Failed to find pod\n");
                        updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                        datasetRepository.findByName(dataName).ifPresent(d -> {
                            d.setTrained(false);
                            datasetRepository.save(d);
                        });
                    }
                } else {
                    logBuilder.append("Training job submission failed\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                }
            } catch (Exception e) {
                updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
            }
        });

        return jobId;
    }

    public String startTesting(String dataName, int imgsz) {
        String jobId = UUID.randomUUID().toString();
        JobStatus status = new JobStatus(jobId, "RUNNING", 0, "");
        jobStatusMap.put(jobId, status);

        datasetRepository.findByName(dataName).ifPresent(d -> {
            d.setTestJobId(jobId);
            d.setTestStatus("RUNNING");
            datasetRepository.save(d);
        });

        executorService.submit(() -> {
            try {
                String yamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + dataName + "-test.yaml";
                
                deleteK8sJob(dataName + "-test-job");
                
                regenerateTestYaml(dataName, imgsz);
                
                File yamlFile = new File(yamlPath);
                StringBuilder logBuilder = new StringBuilder();
                
                if (!yamlFile.exists()) {
                    logBuilder.append("错误: YAML文件不存在: ").append(yamlPath).append("\n");
                    logBuilder.append("请先执行训练操作生成配置文件\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
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
                            Process podProcess = new ProcessBuilder("kubectl", "get", "pods", "-l", "job-name=" + dataName + "-test-job", "-o", "name").start();
                            BufferedReader podReader = new BufferedReader(new InputStreamReader(podProcess.getInputStream(), "UTF-8"));
                            String podLine = podReader.readLine();
                            if (podLine != null && podLine.startsWith("pod/")) {
                                podName = podLine.substring(4);
                                logBuilder.append("Pod创建成功: ").append(podName).append("\n");
                                logBuilder.append("开始获取实时日志...\n");
                                updateStatus(jobId, "RUNNING", 0, logBuilder.toString(), podName);
                                
                                final String finalPodName = podName;
                                datasetRepository.findByName(dataName).ifPresent(dataset -> {
                                    dataset.setTestPodName(finalPodName);
                                    datasetRepository.save(dataset);
                                });
                                break;
                            }
                        } catch (Exception e) {
                            // 忽略错误，继续尝试
                        }
                    }
                    
                    if (podName != null) {
                        final String finalPodName = podName;
                        int progress = 0;
                        
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
                                
                                Process checkProcess = new ProcessBuilder("kubectl", "get", "pod", finalPodName, "-o", "jsonpath={.status.phase}").start();
                                BufferedReader checkReader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream(), "UTF-8"));
                                String phase = checkReader.readLine();
                                checkProcess.waitFor();
                                
                                if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                                    break;
                                }
                            } catch (Exception e) {
                                // 忽略单次轮询错误
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
                            datasetRepository.findByName(dataName).ifPresent(d -> {
                                d.setTested(true);
                                d.setTestStatus("COMPLETED");
                                datasetRepository.save(d);
                            });
                        } else {
                            StringBuilder failLog = new StringBuilder(finalLog);
                            failLog.append("\nTesting failed\n");
                            String finalLogStr = failLog.toString();
                            updateStatus(jobId, "FAILED", 0, finalLogStr, finalPodName);
                            datasetRepository.findByName(dataName).ifPresent(d -> {
                                d.setTested(false);
                                d.setTestStatus("FAILED");
                                datasetRepository.save(d);
                            });
                        }
                    } else {
                        logBuilder.append("Failed to find pod\n");
                        updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                    }
                } else {
                    logBuilder.append("Testing job submission failed\n");
                    updateStatus(jobId, "FAILED", 0, logBuilder.toString());
                }
            } catch (Exception e) {
                updateStatus(jobId, "FAILED", 0, "Error: " + e.getMessage());
            }
        });

        return jobId;
    }

    public String getPodLogs(String podName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "logs",podName);
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder logBuilder = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null) {
                logBuilder.append(line).append("\n");
            }

            int exitCode = process.waitFor();
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

    public JobStatus getJobStatusByDataName(String dataName) {
        Dataset dataset = datasetRepository.findByName(dataName).orElse(null);
        if (dataset == null) {
            return null;
        }
        
        String trainStatus = dataset.getTrainStatus() != null ? dataset.getTrainStatus() : "IDLE";
        
        if ("RUNNING".equals(trainStatus) || "QUEUED".equals(trainStatus)) {
            JobStatus memStatus = dataset.getTrainJobId() != null ? jobStatusMap.get(dataset.getTrainJobId()) : null;
            if (memStatus != null) {
                return memStatus;
            }
        }
        
        int progress = "COMPLETED".equals(trainStatus) ? 100 : (dataset.getTrainProgress() != null ? dataset.getTrainProgress() : 0);
        String log = "";
        
        String jobLog = getJobLogs(dataName + "-train-job");
        if (jobLog != null && !jobLog.isEmpty() && !jobLog.startsWith("Error:") && !jobLog.contains("failed to")) {
            log = jobLog;
            int parsedProgress = parseEpochProgress(log);
            if (parsedProgress > 0) {
                progress = parsedProgress;
            }
        }
        
        if (log.isEmpty() && dataset.getTrainPodName() != null) {
            String podLog = getPodLogs(dataset.getTrainPodName());
            if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error:") && !podLog.startsWith("获取日志中") && !podLog.contains("failed to")) {
                log = podLog;
                int parsedProgress = parseEpochProgress(log);
                if (parsedProgress > 0) {
                    progress = parsedProgress;
                }
            }
        }
        
        if (log.isEmpty()) {
            JobStatus memStatus = dataset.getTrainJobId() != null ? jobStatusMap.get(dataset.getTrainJobId()) : null;
            if (memStatus != null && memStatus.getLog() != null && !memStatus.getLog().isEmpty()) {
                log = memStatus.getLog();
                if (memStatus.getProgress() > 0) {
                    progress = memStatus.getProgress();
                }
            }
        }
        
        if (log.isEmpty()) {
            log = "日志不可用：训练任务已完成，相关资源已被清理。\n" +
                  "训练状态: " + trainStatus + "\n" +
                  "训练进度: " + progress + "%";
        }
        
        return new JobStatus("persisted", trainStatus, progress, log);
    }

    public JobStatus getTestJobStatusByDataName(String dataName) {
        Dataset dataset = datasetRepository.findByName(dataName).orElse(null);
        if (dataset == null) {
            return null;
        }
        
        String testStatus = dataset.getTestStatus() != null ? dataset.getTestStatus() : "IDLE";
        
        if ("RUNNING".equals(testStatus)) {
            JobStatus memStatus = dataset.getTestJobId() != null ? jobStatusMap.get(dataset.getTestJobId()) : null;
            if (memStatus != null) {
                return memStatus;
            }
        }
        
        int progress = "COMPLETED".equals(testStatus) ? 100 : 0;
        String log = "";
        
        String jobLog = getJobLogs(dataName + "-test-job");
        if (jobLog != null && !jobLog.isEmpty() && !jobLog.startsWith("Error:") && !jobLog.contains("failed to")) {
            log = jobLog;
        }
        
        if (log.isEmpty() && dataset.getTestPodName() != null) {
            String podLog = getPodLogs(dataset.getTestPodName());
            if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error:") && !podLog.startsWith("获取日志中") && !podLog.contains("failed to")) {
                log = podLog;
            }
        }
        
        if (log.isEmpty()) {
            JobStatus memStatus = dataset.getTestJobId() != null ? jobStatusMap.get(dataset.getTestJobId()) : null;
            if (memStatus != null && memStatus.getLog() != null && !memStatus.getLog().isEmpty()) {
                log = memStatus.getLog();
                progress = memStatus.getProgress();
            }
        }
        
        if (log.isEmpty()) {
            log = "日志不可用：测试任务已完成，相关资源已被清理。\n" +
                  "测试状态: " + testStatus;
        }
        
        return new JobStatus("persisted", testStatus, progress, log);
    }

    public Map<String, JobStatus> getJobStatusMap() {
        return jobStatusMap;
    }

    public String deleteDataset(String dataName) {
        try {
            StringBuilder logBuilder = new StringBuilder();
            
            String trainYamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + dataName + "-train.yaml";
            String testYamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + dataName + "-test.yaml";
            String processedDir = PROJECT_ROOT + "\\" + dataName + "_processed";
            
            if (new File(trainYamlPath).exists()) {
                ProcessBuilder pb = new ProcessBuilder("kubectl", "delete", "-f", trainYamlPath);
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    logBuilder.append(line).append("\n");
                }
                process.waitFor();
                
                new File(trainYamlPath).delete();
                logBuilder.append("Deleted: ").append(trainYamlPath).append("\n");
            }
            
            if (new File(testYamlPath).exists()) {
                ProcessBuilder pb = new ProcessBuilder("kubectl", "delete", "-f", testYamlPath);
                pb.directory(new File(PROJECT_ROOT));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    logBuilder.append(line).append("\n");
                }
                process.waitFor();
                
                new File(testYamlPath).delete();
                logBuilder.append("Deleted: ").append(testYamlPath).append("\n");
            }
            
            deleteDirectory(new File(processedDir));
            logBuilder.append("Deleted: " + processedDir + "\n");
            
            // 从数据库中删除数据集记录
            datasetRepository.findByName(dataName).ifPresent(dataset -> {
                datasetRepository.delete(dataset);
                logBuilder.append("Deleted dataset from database: " + dataName + "\n");
            });
            
            return logBuilder.length() > 0 ? logBuilder.toString() : "No resources to delete";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
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

    public List<DatasetStatus> getDatasetStatusList() {
        List<DatasetStatus> result = new ArrayList<>();
        
        try {
            List<Dataset> datasets = datasetRepository.findAll();
            
            for (Dataset dataset : datasets) {
                DatasetStatus ds = new DatasetStatus(dataset.getName());
                ds.setPreprocessed(dataset.getPreprocessed());
                ds.setTrained(dataset.getTrained());
                ds.setTested(dataset.getTested());
                ds.setTrainPodName(dataset.getTrainPodName());
                ds.setTestPodName(dataset.getTestPodName());
                ds.setPriority(dataset.getPriority());
                ds.setTrainProgress(dataset.getTrainProgress());
                ds.setInternalTrainStatus(dataset.getTrainStatus());
                ds.setInternalTestStatus(dataset.getTestStatus());
                result.add(ds);
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return result;
    }

    private void refreshDatasets() {
        // 仅刷新 Pod 名称信息，不覆盖训练/测试状态
        try {
            String podsOutput = listPods();
            String[] lines = podsOutput.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    String podName = parts[0];
                    String status = parts[2];
                    
                    String dataName = null;
                    if (podName.contains("-train")) {
                        dataName = podName.substring(0, podName.indexOf("-train"));
                    } else if (podName.contains("-test")) {
                        dataName = podName.substring(0, podName.indexOf("-test"));
                    }
                    
                    if (dataName != null) {
                        datasetRepository.findByName(dataName).ifPresent(d -> {
                            // 只更新 Pod 名称，不覆盖 trained/tested 状态
                            // trained/tested 状态由训练/测试完成逻辑负责更新
                            if (podName.contains("-train") && d.getTrainPodName() == null) {
                                d.setTrainPodName(podName);
                                datasetRepository.save(d);
                            } else if (podName.contains("-test") && d.getTestPodName() == null) {
                                d.setTestPodName(podName);
                                datasetRepository.save(d);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void deleteK8sJob(String jobName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "delete", "job", jobName, "--ignore-not-found=true");
            pb.directory(new File(PROJECT_ROOT));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
            System.out.println("已删除旧Job: " + jobName);
        } catch (Exception e) {
            System.out.println("删除旧Job失败(可忽略): " + jobName + " - " + e.getMessage());
        }
    }

    private void regenerateTrainYaml(String dataName, int epochs, int imgsz) {
        try {
            String yamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + dataName + "-train.yaml";
            Map<String, Object> jobConfig = buildK8sJobConfig(dataName, "train", epochs, imgsz);
            writeYamlFile(yamlPath, jobConfig);
        } catch (Exception e) {
            System.out.println("重新生成训练YAML失败: " + e.getMessage());
        }
    }

    private void regenerateTestYaml(String dataName, int imgsz) {
        try {
            String yamlPath = PROJECT_ROOT + "\\k8s_jobs\\" + dataName + "-test.yaml";
            Map<String, Object> jobConfig = buildK8sJobConfig(dataName, "test", 0, imgsz);
            writeYamlFile(yamlPath, jobConfig);
        } catch (Exception e) {
            System.out.println("重新生成测试YAML失败: " + e.getMessage());
        }
    }

    private Map<String, Object> buildK8sJobConfig(String dataName, String jobType, int epochs, int imgsz) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("python3");
        command.add(jobType + "_yolo.py");
        command.add("--site");
        command.add(dataName + "_processed");
        if ("train".equals(jobType)) {
            command.add("--epochs");
            command.add(String.valueOf(epochs));
            command.add("--imgsz");
            command.add(String.valueOf(imgsz));
        } else {
            command.add("--imgsz");
            command.add(String.valueOf(imgsz));
        }

        Map<String, Object> jobConfig = new java.util.LinkedHashMap<>();
        jobConfig.put("apiVersion", "batch/v1");
        jobConfig.put("kind", "Job");

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("name", dataName + "-" + jobType + "-job");
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("site", dataName);
        labels.put("type", jobType);
        metadata.put("labels", labels);
        jobConfig.put("metadata", metadata);

        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("parallelism", 1);
        spec.put("completions", 1);

        Map<String, Object> template = new java.util.LinkedHashMap<>();
        Map<String, Object> podSpec = new java.util.LinkedHashMap<>();

        java.util.List<Map<String, Object>> containers = new java.util.ArrayList<>();
        Map<String, Object> container = new java.util.LinkedHashMap<>();
        container.put("name", "yolo-container");
        container.put("image", "yolov8-project:latest");
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("command", command);

        java.util.List<Map<String, String>> envList = new java.util.ArrayList<>();
        Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("name", "PYTHONUNBUFFERED");
        env.put("value", "1");
        envList.add(env);
        container.put("env", envList);

        java.util.List<Map<String, String>> volumeMounts = new java.util.ArrayList<>();
        Map<String, String> mount = new java.util.LinkedHashMap<>();
        mount.put("name", "app-volume");
        mount.put("mountPath", "/app");
        volumeMounts.add(mount);
        container.put("volumeMounts", volumeMounts);

        containers.add(container);
        podSpec.put("containers", containers);
        podSpec.put("restartPolicy", "Never");

        java.util.List<Map<String, Object>> volumes = new java.util.ArrayList<>();
        Map<String, Object> volume = new java.util.LinkedHashMap<>();
        volume.put("name", "app-volume");
        Map<String, String> hostPath = new java.util.LinkedHashMap<>();
        hostPath.put("path", "/mnt/host/e/Ancious/Desktop/毕业设计/Code");
        hostPath.put("type", "Directory");
        volume.put("hostPath", hostPath);
        volumes.add(volume);
        podSpec.put("volumes", volumes);

        template.put("spec", podSpec);
        spec.put("template", template);
        spec.put("backoffLimit", 4);
        jobConfig.put("spec", spec);

        return jobConfig;
    }

    private void writeYamlFile(String yamlPath, Map<String, Object> jobConfig) throws IOException {
        File yamlFile = new File(yamlPath);
        yamlFile.getParentFile().mkdirs();
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        try (FileWriter writer = new FileWriter(yamlFile)) {
            yaml.dump(jobConfig, writer);
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
    
    public String saveModel(String dataName, String modelType, String savePath) {
        try {
            String sourceDir = PROJECT_ROOT + "\\runs\\detect\\" + dataName + "_processed_train\\weights";
            
            File sourceDirectory = new File(sourceDir);
            if (!sourceDirectory.exists()) {
                return "错误: 模型目录不存在: " + sourceDir;
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
            
            String destFileName = dataName + "_" + modelType + ".pt";
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
}
