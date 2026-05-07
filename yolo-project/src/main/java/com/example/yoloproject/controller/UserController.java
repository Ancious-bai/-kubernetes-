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
    public ResponseEntity<?> getAllUsers(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!authService.isAdminOrAbove(role)) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "无权限查看用户列表");
            return ResponseEntity.status(403).body(response);
        }
        List<User> users = authService.getAllUsers();
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
    public ResponseEntity<List<OperationLog>> getOperationLogs(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        List<OperationLog> logs = authService.getOperationLogs(role, username);

        if ("ADMIN".equals(role)) {
            logs = logs.stream()
                    .filter(log -> {
                        String logUser = log.getUsername();
                        if (logUser == null || logUser.isEmpty()) return true;
                        if (username.equals(logUser)) return true;
                        try {
                            User u = authService.getUserByUsername(logUser);
                            return u != null && "USER".equals(u.getRole());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(logs);
    }
}
