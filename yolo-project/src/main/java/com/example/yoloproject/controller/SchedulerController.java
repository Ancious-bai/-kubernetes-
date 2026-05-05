package com.example.yoloproject.controller;

import com.example.yoloproject.service.TrainingSchedulerService;
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

    @PostMapping("/add")
    public ResponseEntity<Map<String, String>> addTask(@RequestBody Map<String, String> request) {
        String dataName = request.get("dataName");
        Map<String, String> response = new HashMap<>();
        
        try {
            schedulerService.addTask(dataName);
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
        String dataName = request.get("dataName");
        Map<String, String> response = new HashMap<>();
        
        try {
            schedulerService.removeTask(dataName);
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
        String dataName = (String) request.get("dataName");
        Integer priority = (Integer) request.get("priority");
        Map<String, String> response = new HashMap<>();
        
        try {
            schedulerService.updatePriority(dataName, priority);
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
    public ResponseEntity<Map<String, String>> setMaxConcurrentTasks(@RequestBody Map<String, Object> request) {
        Integer max = (Integer) request.get("max");
        Map<String, String> response = new HashMap<>();
        
        try {
            schedulerService.setMaxConcurrentTasks(max);
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

    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> getQueueStatus() {
        List<Map<String, Object>> status = schedulerService.getQueueStatus();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status/{dataName}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String dataName) {
        Map<String, Object> response = new HashMap<>();
        response.put("dataName", dataName);
        response.put("status", schedulerService.getTaskStatus(dataName));
        response.put("progress", schedulerService.getTaskProgress(dataName));
        return ResponseEntity.ok(response);
    }
}
