package com.example.yoloproject.controller;

import com.example.yoloproject.service.AuthService;
import com.example.yoloproject.service.TrainingSchedulerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    @Autowired
    private TrainingSchedulerService schedulerService;

    @Autowired
    private AuthService authService;

    @PostMapping("/add")
    public ResponseEntity<Map<String, String>> addTask(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String dataName = (String) request.get("dataName");
        Integer epochs = request.get("epochs") != null ? ((Number) request.get("epochs")).intValue() : null;
        Integer imgsz = request.get("imgsz") != null ? ((Number) request.get("imgsz")).intValue() : null;
        String username = (String) httpRequest.getAttribute("username");

        String targetNode = (String) request.get("targetNode");
        Map<String, String> nodeSelector = (Map<String, String>) request.get("nodeSelector");
        Map<String, String> gpuResources = (Map<String, String>) request.get("gpuResources");

        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.addTask(dataName, epochs, imgsz, username,
                    targetNode, nodeSelector, gpuResources);
            int e = epochs != null && epochs > 0 ? epochs : 2;
            int i = imgsz != null && imgsz > 0 ? imgsz : 640;
            String detail = "开始训练: epochs=" + e + ", imgsz=" + i;
            if (targetNode != null) detail += ", node=" + targetNode;
            if (gpuResources != null) detail += ", gpu=" + gpuResources;
            authService.logOperation(null, username, "TRAIN", dataName + "-e" + e + "-i" + i, detail);
            response.put("message", "任务已加入队列");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/remove")
    public ResponseEntity<Map<String, String>> removeTask(@RequestBody Map<String, String> request) {
        String recordName = request.get("recordName");
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.removeTask(recordName);
            response.put("message", "任务已从队列移除");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/default-epochs")
    public ResponseEntity<Map<String, String>> setDefaultEpochs(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        Integer epochs = ((Number) request.get("epochs")).intValue();
        String username = (String) httpRequest.getAttribute("username");
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.setDefaultEpochs(epochs);
            authService.logOperation(null, username, "CONFIG_CHANGE", "defaultEpochs", "默认Epochs更新为: " + epochs);
            response.put("message", "默认训练轮数已更新为: " + epochs);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/default-epochs")
    public ResponseEntity<Map<String, Object>> getDefaultEpochs() {
        Map<String, Object> response = new HashMap<>();
        response.put("epochs", schedulerService.getDefaultEpochs());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/default-imgsz")
    public ResponseEntity<Map<String, String>> setDefaultImgsz(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        Integer imgsz = ((Number) request.get("imgsz")).intValue();
        String username = (String) httpRequest.getAttribute("username");
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.setDefaultImgsz(imgsz);
            authService.logOperation(null, username, "CONFIG_CHANGE", "defaultImgsz", "默认Imgsz更新为: " + imgsz);
            response.put("message", "默认图像尺寸已更新为: " + imgsz);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/default-imgsz")
    public ResponseEntity<Map<String, Object>> getDefaultImgsz() {
        Map<String, Object> response = new HashMap<>();
        response.put("imgsz", schedulerService.getDefaultImgsz());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/scheduling-mode")
    public ResponseEntity<Map<String, String>> setSchedulingMode(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String mode = (String) request.get("mode");
        String username = (String) httpRequest.getAttribute("username");
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.setSchedulingMode(mode);
            authService.logOperation(null, username, "CONFIG_CHANGE", "schedulingMode", "调度模式更新为: " + mode);
            response.put("message", "调度模式已更新为: " + mode);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/scheduling-mode")
    public ResponseEntity<Map<String, Object>> getSchedulingMode() {
        Map<String, Object> response = new HashMap<>();
        response.put("mode", schedulerService.getSchedulingMode());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(schedulerService.getConfig());
    }

    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> getQueueStatus() {
        List<Map<String, Object>> status = schedulerService.getQueueStatus();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status/{recordName}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String recordName) {
        Map<String, Object> response = new HashMap<>();
        response.put("recordName", recordName);
        response.put("status", schedulerService.getTaskStatus(recordName));
        response.put("progress", schedulerService.getTaskProgress(recordName));
        return ResponseEntity.ok(response);
    }
}
