package com.example.yoloproject.controller;

import com.example.yoloproject.dto.DatasetStatus;
import com.example.yoloproject.dto.JobStatus;
import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.DatasetRepository;
import com.example.yoloproject.repository.OperationLogRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import com.example.yoloproject.service.AuthService;
import com.example.yoloproject.service.YoloService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api")
public class YoloController {

    private static final Logger log = LoggerFactory.getLogger(YoloController.class);

    @Autowired
    private YoloService yoloService;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private OperationLogRepository operationLogRepository;

    @Autowired
    private AuthService authService;

    @Value("${app.project-root:./}")
    private String projectRoot;

    @PostMapping("/datasets/upload")
    public ResponseEntity<Map<String, String>> uploadDataset(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dataName", required = false) String dataName,
            HttpServletRequest httpRequest) {

        String username = (String) httpRequest.getAttribute("username");

        if (file.isEmpty()) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "上传文件为空");
            return ResponseEntity.badRequest().body(response);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "dataset.zip";
        }

        if (dataName == null || dataName.isBlank()) {
            dataName = originalFilename.replaceAll("\\.(zip|tar|tar\\.gz|tgz)$", "");
            dataName = dataName.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
            if (dataName.isBlank()) dataName = "dataset_" + System.currentTimeMillis();
        }

        if (datasetRepository.existsByName(dataName)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "数据集已存在: " + dataName);
            response.put("dataName", dataName);
            return ResponseEntity.badRequest().body(response);
        }

        try {
            String workspaceDir = projectRoot.replace("\\", "/");
            if (!workspaceDir.endsWith("/")) workspaceDir += "/";

            Path uploadDir = Paths.get(workspaceDir, dataName);
            Files.createDirectories(uploadDir);

            if (originalFilename.toLowerCase().endsWith(".zip")) {
                extractZipFileSmart(file, uploadDir.toString());
            } else {
                Path targetFile = uploadDir.resolve(originalFilename);
                Files.copy(file.getInputStream(), targetFile);
            }

            Dataset dataset = new Dataset(dataName, workspaceDir + dataName, username);
            datasetRepository.save(dataset);

            authService.logOperation(null, username, "UPLOAD_DATASET", dataName, "上传数据集: " + originalFilename);

            Map<String, String> response = new HashMap<>();
            response.put("message", "数据集上传成功");
            response.put("dataName", dataName);
            response.put("path", workspaceDir + dataName);
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to upload dataset: {}", e.getMessage(), e);
            Map<String, String> response = new HashMap<>();
            response.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private void extractZipFileSmart(MultipartFile zipFile, String targetDir) throws IOException {
        Path targetPath = Paths.get(targetDir);
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            boolean hasSingleRootDir = true;
            String rootDirName = null;
            java.util.List<String> allEntries = new java.util.ArrayList<>();

            while ((entry = zis.getNextEntry()) != null) {
                allEntries.add(entry.getName());
                zis.closeEntry();
            }

            for (String name : allEntries) {
                if (!name.contains("/")) {
                    hasSingleRootDir = false;
                    break;
                }
                String firstPart = name.split("/")[0];
                if (rootDirName == null) {
                    rootDirName = firstPart;
                } else if (!rootDirName.equals(firstPart)) {
                    hasSingleRootDir = false;
                    break;
                }
            }

            if (rootDirName == null) return;

            try (ZipInputStream zis2 = new ZipInputStream(zipFile.getInputStream())) {
                while ((entry = zis2.getNextEntry()) != null) {
                    String entryName = entry.getName();

                    if (hasSingleRootDir) {
                        if (entryName.startsWith(rootDirName + "/")) {
                            entryName = entryName.substring(rootDirName.length() + 1);
                        } else if (entryName.equals(rootDirName + "/") || entryName.equals(rootDirName)) {
                            zis2.closeEntry();
                            continue;
                        }
                    }

                    if (entryName.isEmpty()) {
                        zis2.closeEntry();
                        continue;
                    }

                    Path entryPath = Paths.get(targetDir, entryName);

                    if (entryPath.normalize().startsWith(targetPath.normalize())) {
                        if (entry.isDirectory()) {
                            Files.createDirectories(entryPath);
                        } else {
                            Files.createDirectories(entryPath.getParent());
                            Files.copy(zis2, entryPath);
                        }
                    }
                    zis2.closeEntry();
                }
            }
        }
    }

    @PostMapping("/datasets")
    public ResponseEntity<Map<String, String>> addDataset(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String inputDir = (String) request.get("inputDir");
        String username = (String) httpRequest.getAttribute("username");

        if (inputDir == null || inputDir.isBlank()) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "数据集名称不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        inputDir = inputDir.trim();

        String dataName;
        String effectivePath;

        if (inputDir.startsWith("/") || inputDir.startsWith("/app/data")) {
            effectivePath = inputDir;
            dataName = new File(inputDir).getName();
        } else {
            String workspaceDir = projectRoot.replace("\\", "/");
            if (!workspaceDir.endsWith("/")) workspaceDir += "/";
            effectivePath = workspaceDir + inputDir;
            dataName = inputDir;
        }

        if (datasetRepository.existsByName(dataName)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "数据集已存在");
            response.put("dataName", dataName);
            return ResponseEntity.badRequest().body(response);
        }

        Dataset dataset = new Dataset(dataName, effectivePath, username);
        datasetRepository.save(dataset);

        authService.logOperation(null, username, "ADD_DATASET", dataName, "添加数据集: " + effectivePath);

        Map<String, String> response = new HashMap<>();
        response.put("message", "数据集添加成功");
        response.put("dataName", dataName);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/datasets/batch")
    public ResponseEntity<Map<String, Object>> addDatasetBatch(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        java.util.List<String> dirs = (java.util.List<String>) request.get("dirs");
        String username = (String) httpRequest.getAttribute("username");
        Map<String, Object> response = new HashMap<>();
        int added = 0, skipped = 0;
        java.util.List<String> addedNames = new java.util.ArrayList<>();

        String workspaceDir = projectRoot.replace("\\", "/");
        if (!workspaceDir.endsWith("/")) workspaceDir += "/";

        for (String inputDir : dirs) {
            if (inputDir == null || inputDir.isBlank()) continue;

            String dataName;
            String effectivePath;

            if (inputDir.startsWith("/") || inputDir.startsWith("/app/data")) {
                effectivePath = inputDir;
                dataName = new File(inputDir).getName();
            } else {
                effectivePath = workspaceDir + inputDir;
                dataName = inputDir;
            }

            if (datasetRepository.existsByName(dataName)) {
                skipped++;
                continue;
            }
            Dataset dataset = new Dataset(dataName, effectivePath, username);
            datasetRepository.save(dataset);
            authService.logOperation(null, username, "ADD_DATASET", dataName, "添加数据集: " + effectivePath);
            addedNames.add(dataName);
            added++;
        }

        response.put("added", added);
        response.put("skipped", skipped);
        response.put("names", addedNames);
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/preprocess")
    public ResponseEntity<Map<String, String>> preprocess(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String dataName = (String) request.get("dataName");
        String username = (String) httpRequest.getAttribute("username");

        String inputDir = datasetRepository.findByName(dataName)
                .map(Dataset::getInputPath)
                .orElse(dataName);

        String jobId = yoloService.startPreprocess(inputDir);
        authService.logOperation(null, username, "PREPROCESS", dataName, "预处理: " + inputDir);

        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "预处理任务已启动");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<JobStatus> getStatus(@PathVariable String jobId) {
        JobStatus status = yoloService.getJobStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/pods")
    public ResponseEntity<Map<String, String>> listPods() {
        String pods = yoloService.listPods();
        Map<String, String> response = new HashMap<>();
        response.put("pods", pods);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/datasets")
    public ResponseEntity<List<DatasetStatus>> getDatasets(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        List<DatasetStatus> allDatasets = yoloService.getDatasetStatusList();

        if ("ROOT".equals(role)) {
        } else if ("ADMIN".equals(role)) {
            allDatasets = allDatasets.stream()
                    .filter(d -> username.equals(d.getCreatedBy()) || "USER".equals(getCreatorRole(d.getCreatedBy())))
                    .collect(Collectors.toList());
        } else {
            allDatasets = allDatasets.stream()
                    .filter(d -> username.equals(d.getCreatedBy()))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(allDatasets);
    }

    private String getCreatorRole(String username) {
        try {
            return authService.getUserByUsername(username).getRole();
        } catch (Exception e) {
            return "USER";
        }
    }

    @PostMapping("/model/save")
    public ResponseEntity<Map<String, String>> saveModel(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        String dataName = request.get("dataName");
        String modelType = request.get("modelType");
        String savePath = request.get("savePath");
        String recordName = request.get("recordName");
        String username = (String) httpRequest.getAttribute("username");

        String result = yoloService.saveModel(dataName, modelType, savePath, recordName);

        Map<String, String> response = new HashMap<>();
        if (!result.startsWith("错误") && !result.startsWith("保存失败")) {
            authService.logOperation(null, username, "SAVE_MODEL", recordName != null ? recordName : dataName, "保存模型: " + modelType);
            response.put("message", "模型保存成功");
            response.put("path", result);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } else {
            response.put("message", result);
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/model/list")
    public ResponseEntity<List<String>> getSavedModels() {
        List<String> models = yoloService.getSavedModels();
        return ResponseEntity.ok(models);
    }

    @DeleteMapping("/datasets/{dataName}")
    public ResponseEntity<Map<String, String>> deleteDataset(@PathVariable String dataName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        Dataset dataset = datasetRepository.findByName(dataName).orElse(null);
        if (dataset == null) {
            Map<String, String> nf = new HashMap<>();
            nf.put("message", "数据集不存在");
            return ResponseEntity.status(404).body(nf);
        }
        if (!"ROOT".equals(role) && !"ADMIN".equals(role) && !username.equals(dataset.getCreatedBy())) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "无权限删除此数据集");
            return ResponseEntity.status(403).body(response);
        }

        String result = yoloService.deleteDataset(dataName);
        if (result != null && result.startsWith("Error:")) {
            Map<String, String> err = new HashMap<>();
            err.put("message", result);
            err.put("status", "error");
            return ResponseEntity.status(500).body(err);
        }
        try {
            authService.logOperation(null, username, "DELETE_DATASET", dataName, "删除数据集");
        } catch (Exception ignore) {
        }

        Map<String, String> response = new HashMap<>();
        response.put("message", "删除完成");
        response.put("result", result);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> test(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String dataName = (String) request.get("dataName");
        int imgsz = request.get("imgsz") != null ? ((Number) request.get("imgsz")).intValue() : 640;
        String recordName = (String) request.get("recordName");
        String username = (String) httpRequest.getAttribute("username");
        if (recordName == null || recordName.isEmpty()) {
            recordName = dataName + "-i" + imgsz;
        }
        String jobId = yoloService.startTesting(dataName, imgsz, recordName);

        authService.logOperation(null, username, "TEST", recordName, "测试: imgsz=" + imgsz);

        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "测试任务已启动");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/training-records")
    public ResponseEntity<Map<String, Object>> createTrainingRecord(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String dataName = (String) request.get("dataName");
        int epochs = request.get("epochs") != null ? ((Number) request.get("epochs")).intValue() : 2;
        int imgsz = request.get("imgsz") != null ? ((Number) request.get("imgsz")).intValue() : 640;
        int priority = request.get("priority") != null ? ((Number) request.get("priority")).intValue() : 5;
        String username = (String) httpRequest.getAttribute("username");

        String recordName = dataName + "-e" + epochs + "-i" + imgsz;

        if (trainingRecordRepository.existsByRecordName(recordName)) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", "训练记录已存在: " + recordName);
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }

        TrainingRecord record = new TrainingRecord(dataName, epochs, imgsz, username != null ? username : "system");
        record.setTrainStatus("IDLE");
        record.setPriority(priority);
        trainingRecordRepository.save(record);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "训练记录创建成功");
        response.put("recordName", recordName);
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/training-records")
    public ResponseEntity<List<TrainingRecord>> getAllTrainingRecords(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        List<TrainingRecord> records;
        if ("ROOT".equals(role) || "ADMIN".equals(role)) {
            records = trainingRecordRepository.findAll();
        } else {
            records = trainingRecordRepository.findByCreatedBy(username);
        }
        return ResponseEntity.ok(records);
    }

    @GetMapping("/training-records/{recordName}/train-log")
    public ResponseEntity<Map<String, Object>> getTrainLogByRecord(@PathVariable String recordName) {
        Map<String, Object> response = new HashMap<>();
        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            response.put("log", "训练记录不存在");
            response.put("status", "UNKNOWN");
            return ResponseEntity.ok(response);
        }

        String logContent = "";
        String status = record.getTrainStatus() != null ? record.getTrainStatus() : "IDLE";

        if ("QUEUED".equals(status)) {
            logContent = "训练任务正在排队等待中...\n记录: " + recordName + "\n参数: epochs=" + record.getEpochs() + ", imgsz=" + record.getImgsz() + "\n状态: 排队中\n等待其他任务完成后将自动开始训练...";
        } else if ("IDLE".equals(status) && record.getTrainJobId() == null) {
            logContent = "训练任务尚未启动\n记录: " + recordName + "\n参数: epochs=" + record.getEpochs() + ", imgsz=" + record.getImgsz() + "\n状态: 待训练\n请点击训练按钮开始训练";
        } else if (record.getTrainJobId() != null) {
            JobStatus jobStatus = yoloService.getJobStatus(record.getTrainJobId());
            if (jobStatus != null && jobStatus.getLog() != null && !jobStatus.getLog().isEmpty()) {
                logContent = jobStatus.getLog();
                status = jobStatus.getStatus();
            }
        }

        if (logContent.isEmpty() && record.getTrainPodName() != null) {
            String podLog = yoloService.getPodLogs(record.getTrainPodName());
            if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error:") && !podLog.contains("failed to")) {
                logContent = podLog;
            }
        }

        if (logContent.isEmpty()) {
            String jobLog = yoloService.getJobLogs(recordName + "-train-job");
            if (jobLog != null && !jobLog.isEmpty() && !jobLog.startsWith("Error:") && !jobLog.contains("failed to")) {
                logContent = jobLog;
            }
        }

        if (logContent.isEmpty() && record.getTrainLogFile() != null && !record.getTrainLogFile().isBlank()) {
            String fileRead = yoloService.readLogContentByFileName(record.getTrainLogFile());
            if (fileRead != null && !fileRead.isEmpty()) {
                logContent = fileRead;
            }
        }

        if (logContent.isEmpty()) {
            String fileLog = yoloService.readLogFromFile(recordName + "-train");
            if (fileLog != null && !fileLog.isEmpty()) {
                logContent = fileLog;
            }
        }

        if (logContent.isEmpty()) {
            logContent = "日志不可用：训练任务已完成，相关资源已被清理。\n训练状态: " + status;
        }

        response.put("log", logContent);
        response.put("status", status);
        response.put("progress", record.getTrainProgress() != null ? record.getTrainProgress() : 0);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/training-records/{recordName}/test-log")
    public ResponseEntity<Map<String, Object>> getTestLogByRecord(@PathVariable String recordName) {
        Map<String, Object> response = new HashMap<>();
        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            response.put("log", "训练记录不存在");
            response.put("status", "UNKNOWN");
            return ResponseEntity.ok(response);
        }

        String logContent = "";
        String status = record.getTestStatus() != null ? record.getTestStatus() : "IDLE";

        if (record.getTestJobId() != null) {
            JobStatus jobStatus = yoloService.getJobStatus(record.getTestJobId());
            if (jobStatus != null && jobStatus.getLog() != null && !jobStatus.getLog().isEmpty()) {
                logContent = jobStatus.getLog();
                status = jobStatus.getStatus();
            }
        }

        if (logContent.isEmpty() && record.getTestPodName() != null) {
            String podLog = yoloService.getPodLogs(record.getTestPodName());
            if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error:") && !podLog.startsWith("获取日志中") && !podLog.contains("failed to")) {
                logContent = podLog;
            }
        }

        if (logContent.isEmpty()) {
            String jobLog = yoloService.getJobLogs(recordName + "-test-job");
            if (jobLog != null && !jobLog.isEmpty() && !jobLog.startsWith("Error:") && !jobLog.contains("failed to")) {
                logContent = jobLog;
            }
        }

        if (logContent.isEmpty() && record.getTestLogFile() != null && !record.getTestLogFile().isBlank()) {
            String fileRead = yoloService.readLogContentByFileName(record.getTestLogFile());
            if (fileRead != null && !fileRead.isEmpty()) {
                logContent = fileRead;
            }
        }

        if (logContent.isEmpty()) {
            String fileLog = yoloService.readLogFromFile(recordName + "-test");
            if (fileLog != null && !fileLog.isEmpty()) {
                logContent = fileLog;
            }
        }

        if (logContent.isEmpty()) {
            logContent = "日志不可用：测试任务已完成，相关资源已被清理。\n测试状态: " + status;
        }

        response.put("log", logContent);
        response.put("status", status);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/training-records/{recordName}")
    public ResponseEntity<Map<String, String>> deleteTrainingRecord(@PathVariable String recordName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record != null && !"ROOT".equals(role) && !"ADMIN".equals(role) && !username.equals(record.getCreatedBy())) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "无权限删除此训练记录");
            return ResponseEntity.status(403).body(response);
        }

        boolean removed;
        try {
            removed = yoloService.deleteTrainingRecord(recordName);
        } catch (IllegalStateException ex) {
            Map<String, String> err = new HashMap<>();
            err.put("message", ex.getMessage() != null ? ex.getMessage() : "删除失败");
            err.put("status", "error");
            return ResponseEntity.status(500).body(err);
        }
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        try {
            authService.logOperation(null, username, "DELETE_RECORD", recordName, "删除训练记录");
        } catch (Exception ignore) {
        }

        Map<String, String> response = new HashMap<>();
        response.put("message", "训练记录已删除");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupAll(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role)) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", "仅 ROOT 用户可执行清理操作");
            response.put("status", "error");
            return ResponseEntity.status(403).body(response);
        }

        Map<String, Object> result = yoloService.cleanupAll();
        operationLogRepository.deleteAll();

        result.put("message", "系统已重置为干净状态");
        result.put("status", "success");
        return ResponseEntity.ok(result);
    }
}
