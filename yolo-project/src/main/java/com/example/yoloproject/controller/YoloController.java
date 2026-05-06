package com.example.yoloproject.controller;

import com.example.yoloproject.dto.DatasetStatus;
import com.example.yoloproject.dto.JobStatus;
import com.example.yoloproject.dto.ProcessRequest;
import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.repository.DatasetRepository;
import com.example.yoloproject.service.YoloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.swing.*;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class YoloController {

    @Autowired
    private YoloService yoloService;
    
    @Autowired
    private DatasetRepository datasetRepository;

    @GetMapping("/dialog/folder")
    public ResponseEntity<Map<String, String>> openFolderDialog(@RequestParam(required = false) String title) {
        Map<String, String> response = new HashMap<>();
        
        try {
            String dialogTitle = title != null ? title : "选择文件夹";
            
            String[] result = new String[1];
            
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame();
                frame.setAlwaysOnTop(true);
                frame.setUndecorated(true);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fileChooser.setDialogTitle(dialogTitle);
                fileChooser.setApproveButtonText("选择");
                fileChooser.setMultiSelectionEnabled(false);
                
                int userSelection = fileChooser.showOpenDialog(frame);
                if (userSelection == JFileChooser.APPROVE_OPTION) {
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
        } catch (Exception e) {
            response.put("path", "");
            response.put("status", "error");
            response.put("message", e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/preprocess")
    public ResponseEntity<Map<String, String>> preprocess(@RequestBody Map<String, Object> request) {
        String dataName = (String) request.get("inputDir");
        int epochs = request.get("epochs") != null ? ((Number) request.get("epochs")).intValue() : 2;
        int imgsz = request.get("imgsz") != null ? ((Number) request.get("imgsz")).intValue() : 640;
        
        String inputDir = datasetRepository.findByName(dataName)
                .map(Dataset::getInputPath)
                .orElse(dataName);
        
        String jobId = yoloService.startPreprocess(inputDir, epochs, imgsz);
        
        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "预处理任务已启动");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/datasets")
    public ResponseEntity<Map<String, String>> addDataset(@RequestBody ProcessRequest request) {
        String inputDir = request.getInputDir();
        String dataName = new java.io.File(inputDir).getName();
        
        // 检查是否已存在
        if (datasetRepository.existsByName(dataName)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "数据集已存在");
            response.put("dataName", dataName);
            return ResponseEntity.badRequest().body(response);
        }
        
        // 保存到数据库
        Dataset dataset = new Dataset(dataName, inputDir);
        datasetRepository.save(dataset);
        

        
        Map<String, String> response = new HashMap<>();
        response.put("message", "数据集添加成功");
        response.put("dataName", dataName);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/train")
    public ResponseEntity<Map<String, String>> train(@RequestBody Map<String, Object> request) {
        String dataName = (String) request.get("inputDir");
        int epochs = request.get("epochs") != null ? ((Number) request.get("epochs")).intValue() : 2;
        int imgsz = request.get("imgsz") != null ? ((Number) request.get("imgsz")).intValue() : 640;
        String jobId = yoloService.startTraining(dataName, epochs, imgsz);
        
        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "训练任务已启动");
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

    @GetMapping("/datasets/{dataName}/train-log")
    public ResponseEntity<Map<String, Object>> getTrainLogByDataName(@PathVariable String dataName) {
        Map<String, Object> response = new HashMap<>();
        JobStatus status = yoloService.getJobStatusByDataName(dataName);
        if (status != null) {
            response.put("log", status.getLog());
            response.put("status", status.getStatus());
            response.put("progress", status.getProgress());
            response.put("podName", status.getPodName());
        } else {
            response.put("log", "");
            response.put("status", "UNKNOWN");
            response.put("progress", 0);
            response.put("podName", null);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/datasets/{dataName}/test-log")
    public ResponseEntity<Map<String, Object>> getTestLogByDataName(@PathVariable String dataName) {
        Map<String, Object> response = new HashMap<>();
        JobStatus status = yoloService.getTestJobStatusByDataName(dataName);
        if (status != null) {
            response.put("log", status.getLog());
            response.put("status", status.getStatus());
            response.put("progress", status.getProgress());
            response.put("podName", status.getPodName());
        } else {
            response.put("log", "");
            response.put("status", "UNKNOWN");
            response.put("progress", 0);
            response.put("podName", null);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pods")
    public ResponseEntity<Map<String, String>> listPods() {
        String pods = yoloService.listPods();
        Map<String, String> response = new HashMap<>();
        response.put("pods", pods);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pods/{podName}/logs")
    public ResponseEntity<Map<String, String>> getPodLogs(@PathVariable String podName) {
        try {
            String decodedPodName = java.net.URLDecoder.decode(podName, "UTF-8");
            String logs = yoloService.getPodLogs(decodedPodName);
            Map<String, String> response = new HashMap<>();
            response.put("podName", decodedPodName);
            response.put("logs", logs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String logs = yoloService.getPodLogs(podName);
            Map<String, String> response = new HashMap<>();
            response.put("podName", podName);
            response.put("logs", logs);
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/datasets")
    public ResponseEntity<List<DatasetStatus>> getDatasets() {
        List<DatasetStatus> datasets = yoloService.getDatasetStatusList();
        return ResponseEntity.ok(datasets);
    }

    @PostMapping("/model/save")
    public ResponseEntity<Map<String, String>> saveModel(@RequestBody Map<String, String> request) {
        String dataName = request.get("dataName");
        String modelType = request.get("modelType");
        String savePath = request.get("savePath");
        
        String result = yoloService.saveModel(dataName, modelType, savePath);
        
        Map<String, String> response = new HashMap<>();
        if (!result.startsWith("错误") && !result.startsWith("保存失败")) {
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
    public ResponseEntity<Map<String, String>> deleteDataset(@PathVariable String dataName) {
        String result = yoloService.deleteDataset(dataName);
        Map<String, String> response = new HashMap<>();
        response.put("message", "删除完成");
        response.put("result", result);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> test(@RequestBody Map<String, Object> request) {
        String dataName = (String) request.get("inputDir");
        int imgsz = request.get("imgsz") != null ? ((Number) request.get("imgsz")).intValue() : 640;
        String jobId = yoloService.startTesting(dataName, imgsz);
        
        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "测试任务已启动");
        return ResponseEntity.ok(response);
    }
}
