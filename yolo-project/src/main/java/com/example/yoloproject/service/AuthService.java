package com.example.yoloproject.service;

import com.example.yoloproject.config.JwtUtil;
import com.example.yoloproject.entity.OperationLog;
import com.example.yoloproject.entity.User;
import com.example.yoloproject.repository.OperationLogRepository;
import com.example.yoloproject.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OperationLogRepository operationLogRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostConstruct
    public void init() {
        try {
            if (!userRepository.existsByUsername("root")) {
                User root = new User("root", passwordEncoder.encode("root123"), "ROOT", "SYSTEM");
                userRepository.save(root);
                System.out.println("==================================");
                System.out.println("已创建默认root用户");
                System.out.println("用户名: root");
                System.out.println("密码: root123");
                System.out.println("==================================");
            } else {
                System.out.println("root 用户已存在，跳过创建");
            }
        } catch (Exception e) {
            System.err.println("创建默认用户失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String login(String username, String password) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
    }

    public User register(String username, String password, String role, String operatorUsername, String operatorRole) {
        if (!"ROOT".equals(operatorRole) && !"ADMIN".equals(operatorRole)) {
            throw new IllegalArgumentException("无权限创建用户");
        }
        if ("ADMIN".equals(operatorRole) && !"USER".equals(role)) {
            throw new IllegalArgumentException("管理员只能创建普通用户");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User(username, passwordEncoder.encode(password), role, operatorUsername);
        User saved = userRepository.save(user);
        logOperation(null, operatorUsername, "CREATE_USER", username, "创建用户，角色: " + role);
        return saved;
    }

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username).orElse(null);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public List<User> getUsersForAdmin(String adminUsername) {
        return userRepository.findAll().stream()
                .filter(u -> adminUsername.equals(u.getCreatedBy()) || adminUsername.equals(u.getUsername()))
                .collect(Collectors.toList());
    }

    public void deleteUser(Long userId, String operatorRole, String operatorUsername) {
        if (!"ROOT".equals(operatorRole) && !"ADMIN".equals(operatorRole)) {
            throw new IllegalArgumentException("无权限删除用户");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if ("ROOT".equals(user.getRole())) {
            throw new IllegalArgumentException("无法删除ROOT用户");
        }
        if ("ADMIN".equals(operatorRole)) {
            if ("ADMIN".equals(user.getRole())) {
                throw new IllegalArgumentException("管理员无法删除其他管理员");
            }
            if (!operatorUsername.equals(user.getCreatedBy())) {
                throw new IllegalArgumentException("只能删除自己创建的用户");
            }
        }
        userRepository.deleteById(userId);
        logOperation(null, operatorUsername, "DELETE_USER", user.getUsername(), "删除用户");
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入旧密码");
        }
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入新密码");
        }
        if (newPassword.length() < 4) {
            throw new IllegalArgumentException("新密码长度不能少于4位");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("旧密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        logOperation(null, username, "CHANGE_PASSWORD", username, "修改密码");
    }

    public void logOperation(Long userId, String username, String action, String target, String detail) {
        if (userId == null && username != null && !username.isEmpty()) {
            userId = userRepository.findByUsername(username).map(User::getId).orElse(null);
        }
        OperationLog log = new OperationLog(userId, username, action, target, detail);
        operationLogRepository.save(log);
    }

    public List<OperationLog> getOperationLogs(String role, String username) {
        if ("ROOT".equals(role)) {
            return operationLogRepository.findAllByOrderByCreatedAtDesc();
        }
        if ("ADMIN".equals(role)) {
            List<User> myUsers = userRepository.findByCreatedBy(username);
            List<String> visibleUsernames = myUsers.stream().map(User::getUsername).collect(Collectors.toList());
            visibleUsernames.add(username);
            return operationLogRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(l -> l.getUsername() == null || visibleUsernames.contains(l.getUsername()))
                    .collect(Collectors.toList());
        }
        return operationLogRepository.findByUsernameOrderByCreatedAtDesc(username);
    }

    public boolean isAdminOrAbove(String role) {
        return "ROOT".equals(role) || "ADMIN".equals(role);
    }
}
