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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.awt.HeadlessException;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class YoloController {

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

    @GetMapping("/dialog/folder")
    public ResponseEntity<Map<String, String>> openFolderDialog(@RequestParam(required = false) String title) {
        Map<String, String> response = new HashMap<>();

        if (java.awt.GraphicsEnvironment.isHeadless()) {
            response.put("path", "");
            response.put("status", "error");
            response.put("message", "Server is running in headless mode");
            return ResponseEntity.ok(response);
        }

        try {
            String dialogTitle = title != null ? title : "选择文件夹";

            String[] result = new String[1];

            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JFrame frame = new javax.swing.JFrame();
                frame.setAlwaysOnTop(true);
                frame.setUndecorated(true);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);

                javax.swing.JFileChooser fileChooser = new javax.swing.JFileChooser();
                fileChooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
                fileChooser.setDialogTitle(dialogTitle);
                fileChooser.setApproveButtonText("选择");
                fileChooser.setMultiSelectionEnabled(false);

                int userSelection = fileChooser.showOpenDialog(frame);
                if (userSelection == javax.swing.JFileChooser.APPROVE_OPTION) {
                    result[0] = fileChooser.getSelectedFile().getAbsolutePath();
                }

                frame.dispose();
            });

            if (result[0] != null && !result[0].isEmpty()) {
                response.put("path", result[0]);
                response.put("status", "success");
            } else {
                response.put("path", "");
                response.put("status", "cancelled");
            }
        } catch (HeadlessException e) {
            response.put("path", "");
            response.put("status", "error");
            response.put("message", "Headless environment");
        } catch (Exception e) {
            response.put("path", "");
            response.put("status", "error");
            response.put("message", e.getMessage());
        }

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
        authService.logOperation(null, username, "PREPROCESS", dataName, "预处理: python preprocess.py --input_dir " + inputDir);

        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "预处理任务已启动");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/datasets")
    public ResponseEntity<Map<String, String>> addDataset(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String inputDir = (String) request.get("inputDir");
        if (inputDir == null || !isValidDatasetFilesystemPath(inputDir)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "数据路径无效：请填写包含上级目录的完整路径（不可仅为文件夹名称）。浏览器拖拽若无法识别路径，请使用「浏览」或手动粘贴路径。");
            return ResponseEntity.badRequest().body(response);
        }
        String dataName = new File(inputDir).getName();
        String username = (String) httpRequest.getAttribute("username");

        if (datasetRepository.existsByName(dataName)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "数据集已存在");
            response.put("dataName", dataName);
            return ResponseEntity.badRequest().body(response);
        }

        Dataset dataset = new Dataset(dataName, inputDir, username);
        datasetRepository.save(dataset);

        authService.logOperation(null, username, "ADD_DATASET", dataName, "添加数据集: " + inputDir);

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

        for (String inputDir : dirs) {
            if (inputDir == null || !isValidDatasetFilesystemPath(inputDir)) {
                Map<String, Object> bad = new HashMap<>();
                bad.put("message", "路径无效（需完整路径，不能仅为文件夹名）: " + inputDir);
                bad.put("status", "error");
                return ResponseEntity.badRequest().body(bad);
            }
            String dataName = new File(inputDir).getName();
            if (datasetRepository.existsByName(dataName)) {
                skipped++;
                continue;
            }
            Dataset dataset = new Dataset(dataName, inputDir, username);
            datasetRepository.save(dataset);
            authService.logOperation(null, username, "ADD_DATASET", dataName, "添加数据集: " + inputDir);
            addedNames.add(dataName);
            added++;
        }

        response.put("added", added);
        response.put("skipped", skipped);
        response.put("names", addedNames);
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    /** 拒绝仅含文件夹名（如 data1）的路径，避免预处理找不到目录 */
    private boolean isValidDatasetFilesystemPath(String inputDir) {
        if (inputDir == null || inputDir.isBlank()) {
            return false;
        }
        File f = new File(inputDir.trim());
        return f.getParentFile() != null;
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

        String log = "";
        String status = record.getTrainStatus() != null ? record.getTrainStatus() : "IDLE";

        if ("QUEUED".equals(status)) {
            log = "训练任务正在排队等待中...\n记录: " + recordName + "\n参数: epochs=" + record.getEpochs() + ", imgsz=" + record.getImgsz() + "\n状态: 排队中\n等待其他任务完成后将自动开始训练...";
        } else if ("IDLE".equals(status) && record.getTrainJobId() == null) {
            log = "训练任务尚未启动\n记录: " + recordName + "\n参数: epochs=" + record.getEpochs() + ", imgsz=" + record.getImgsz() + "\n状态: 待训练\n请点击训练按钮开始训练";
        } else if (record.getTrainJobId() != null) {
            JobStatus jobStatus = yoloService.getJobStatus(record.getTrainJobId());
            if (jobStatus != null && jobStatus.getLog() != null && !jobStatus.getLog().isEmpty()) {
                log = jobStatus.getLog();
                status = jobStatus.getStatus();
            }
        }

        if (log.isEmpty() && record.getTrainPodName() != null) {
            String podLog = yoloService.getPodLogs(record.getTrainPodName());
            if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error:") && !podLog.contains("failed to")) {
                log = podLog;
            }
        }

        if (log.isEmpty()) {
            String jobLog = yoloService.getJobLogs(recordName + "-train-job");
            if (jobLog != null && !jobLog.isEmpty() && !jobLog.startsWith("Error:") && !jobLog.contains("failed to")) {
                log = jobLog;
            }
        }

        if (log.isEmpty() && record.getTrainLogFile() != null && !record.getTrainLogFile().isBlank()) {
            String fileRead = yoloService.readLogContentByFileName(record.getTrainLogFile());
            if (fileRead != null && !fileRead.isEmpty()) {
                log = fileRead;
            }
        }

        if (log.isEmpty()) {
            String fileLog = yoloService.readLogFromFile(recordName + "-train");
            if (fileLog != null && !fileLog.isEmpty()) {
                log = fileLog;
            }
        }

        if (log.isEmpty()) {
            log = "日志不可用：训练任务已完成，相关资源已被清理。\n训练状态: " + status;
        }

        response.put("log", log);
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

        String log = "";
        String status = record.getTestStatus() != null ? record.getTestStatus() : "IDLE";

        if (record.getTestJobId() != null) {
            JobStatus jobStatus = yoloService.getJobStatus(record.getTestJobId());
            if (jobStatus != null && jobStatus.getLog() != null && !jobStatus.getLog().isEmpty()) {
                log = jobStatus.getLog();
                status = jobStatus.getStatus();
            }
        }

        if (log.isEmpty() && record.getTestPodName() != null) {
            String podLog = yoloService.getPodLogs(record.getTestPodName());
            if (podLog != null && !podLog.isEmpty() && !podLog.startsWith("Error:") && !podLog.startsWith("获取日志中") && !podLog.contains("failed to")) {
                log = podLog;
            }
        }

        if (log.isEmpty()) {
            String jobLog = yoloService.getJobLogs(recordName + "-test-job");
            if (jobLog != null && !jobLog.isEmpty() && !jobLog.startsWith("Error:") && !jobLog.contains("failed to")) {
                log = jobLog;
            }
        }

        if (log.isEmpty() && record.getTestLogFile() != null && !record.getTestLogFile().isBlank()) {
            String fileRead = yoloService.readLogContentByFileName(record.getTestLogFile());
            if (fileRead != null && !fileRead.isEmpty()) {
                log = fileRead;
            }
        }

        if (log.isEmpty()) {
            String fileLog = yoloService.readLogFromFile(recordName + "-test");
            if (fileLog != null && !fileLog.isEmpty()) {
                log = fileLog;
            }
        }

        if (log.isEmpty()) {
            log = "日志不可用：测试任务已完成，相关资源已被清理。\n测试状态: " + status;
        }

        response.put("log", log);
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
