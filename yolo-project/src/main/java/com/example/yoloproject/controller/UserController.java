package com.example.yoloproject.controller;

import com.example.yoloproject.entity.OperationLog;
import com.example.yoloproject.entity.User;
import com.example.yoloproject.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private AuthService authService;

    @GetMapping
    public ResponseEntity<?> getAllUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            HttpServletRequest httpRequest) {
        String currentRole = (String) httpRequest.getAttribute("role");
        if (!authService.isAdminOrAbove(currentRole)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "无权限查看用户列表");
            return ResponseEntity.status(403).body(response);
        }
        List<User> users = authService.getAllUsers();

        if (search != null && !search.trim().isEmpty()) {
            String keyword = search.trim().toLowerCase();
            users = users.stream()
                    .filter(u -> u.getUsername().toLowerCase().contains(keyword))
                    .collect(Collectors.toList());
        }
        if (role != null && !role.trim().isEmpty()) {
            users = users.stream()
                    .filter(u -> role.equals(u.getRole()))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(users);
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        String operatorRole = (String) httpRequest.getAttribute("role");
        String operatorUsername = (String) httpRequest.getAttribute("username");
        Map<String, Object> response = new HashMap<>();
        try {
            authService.updateUserRole(id, request.get("role"), operatorUsername, operatorRole);
            response.put("message", "角色更新成功");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id, HttpServletRequest httpRequest) {
        String operatorRole = (String) httpRequest.getAttribute("role");
        Map<String, Object> response = new HashMap<>();
        try {
            authService.deleteUser(id, operatorRole);
            response.put("message", "用户已删除");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<List<OperationLog>> getOperationLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String currentUsername = (String) httpRequest.getAttribute("username");

        List<OperationLog> logs = authService.getOperationLogs(role, currentUsername);

        if ("ADMIN".equals(role)) {
            logs = logs.stream()
                    .filter(log -> {
                        String logUser = log.getUsername();
                        if (logUser == null || logUser.isEmpty()) return true;
                        if (currentUsername.equals(logUser)) return true;
                        try {
                            User u = authService.getUserByUsername(logUser);
                            return u != null && "USER".equals(u.getRole());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
        }

        if (username != null && !username.trim().isEmpty()) {
            String kw = username.trim().toLowerCase();
            logs = logs.stream()
                    .filter(log -> log.getUsername() != null && log.getUsername().toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }
        if (action != null && !action.trim().isEmpty()) {
            logs = logs.stream()
                    .filter(log -> action.equals(log.getAction()))
                    .collect(Collectors.toList());
        }
        if (startTime != null && !startTime.trim().isEmpty()) {
            try {
                java.time.LocalDateTime start = java.time.LocalDateTime.parse(startTime);
                logs = logs.stream()
                        .filter(log -> log.getCreatedAt() != null && !log.getCreatedAt().isBefore(start))
                        .collect(Collectors.toList());
            } catch (Exception e) {
            }
        }
        if (endTime != null && !endTime.trim().isEmpty()) {
            try {
                java.time.LocalDateTime end = java.time.LocalDateTime.parse(endTime);
                logs = logs.stream()
                        .filter(log -> log.getCreatedAt() != null && !log.getCreatedAt().isAfter(end))
                        .collect(Collectors.toList());
            } catch (Exception e) {
            }
        }

        return ResponseEntity.ok(logs);
    }

    @PutMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        String username = (String) httpRequest.getAttribute("username");
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");
        Map<String, Object> response = new HashMap<>();
        try {
            authService.changePassword(username, oldPassword, newPassword);
            response.put("message", "密码修改成功");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }
}
