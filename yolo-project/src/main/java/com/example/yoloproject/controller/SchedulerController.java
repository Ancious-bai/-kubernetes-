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
        Integer priority = request.get("priority") != null ? ((Number) request.get("priority")).intValue() : null;
        String username = (String) httpRequest.getAttribute("username");
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.addTask(dataName, epochs, imgsz, username, priority);
            int e = epochs != null && epochs > 0 ? epochs : 2;
            int i = imgsz != null && imgsz > 0 ? imgsz : 640;
            authService.logOperation(null, username, "TRAIN", dataName + "-e" + e + "-i" + i, "开始训练: epochs=" + e + ", imgsz=" + i);
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

    @PostMapping("/priority")
    public ResponseEntity<Map<String, String>> updatePriority(@RequestBody Map<String, Object> request) {
        String recordName = (String) request.get("recordName");
        Integer priority = ((Number) request.get("priority")).intValue();
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.updatePriority(recordName, priority);
            response.put("message", "优先级已更新");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/max-concurrent")
    public ResponseEntity<Map<String, String>> setMaxConcurrentTasks(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        Integer max = ((Number) request.get("max")).intValue();
        String username = (String) httpRequest.getAttribute("username");
        Map<String, String> response = new HashMap<>();

        try {
            schedulerService.setMaxConcurrentTasks(max);
            authService.logOperation(null, username, "CONFIG_CHANGE", "maxConcurrent", "同时训练数更新为: " + max);
            response.put("message", "最大同时训练数已更新为: " + max);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/max-concurrent")
    public ResponseEntity<Map<String, Object>> getMaxConcurrentTasks() {
        Map<String, Object> response = new HashMap<>();
        response.put("max", schedulerService.getMaxConcurrentTasks());
        return ResponseEntity.ok(response);
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

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> response = new HashMap<>();
        response.put("maxConcurrentTasks", schedulerService.getMaxConcurrentTasks());
        response.put("defaultEpochs", schedulerService.getDefaultEpochs());
        response.put("defaultImgsz", schedulerService.getDefaultImgsz());
        return ResponseEntity.ok(response);
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
