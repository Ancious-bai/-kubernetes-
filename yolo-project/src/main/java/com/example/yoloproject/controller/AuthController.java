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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        Map<String, Object> response = new HashMap<>();
        try {
            String token = authService.login(username, password);
            User user = authService.getUserByUsername(username);
            response.put("token", token);
            response.put("username", user.getUsername());
            response.put("role", user.getRole());
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        String operatorRole = (String) httpRequest.getAttribute("role");
        Map<String, Object> response = new HashMap<>();
        try {
            User user = authService.register(
                    request.get("username"),
                    request.get("password"),
                    request.getOrDefault("role", "USER"),
                    operatorRole
            );
            response.put("message", "用户创建成功");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(HttpServletRequest httpRequest) {
        String username = (String) httpRequest.getAttribute("username");
        String role = (String) httpRequest.getAttribute("role");
        Map<String, Object> response = new HashMap<>();
        response.put("username", username);
        response.put("role", role);
        return ResponseEntity.ok(response);
    }
}
