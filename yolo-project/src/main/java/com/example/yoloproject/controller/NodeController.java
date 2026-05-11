package com.example.yoloproject.controller;

import com.example.yoloproject.entity.NodeInfo;
import com.example.yoloproject.entity.NodeLog;
import com.example.yoloproject.service.AuthService;
import com.example.yoloproject.service.NodeManagementService;
import com.example.yoloproject.service.YoloService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/nodes")
public class NodeController {

    @Autowired
    private NodeManagementService nodeManagementService;

    @Autowired
    private AuthService authService;

    @Autowired
    private YoloService yoloService;

    @Autowired
    private com.example.yoloproject.service.K8sClientService k8sClientService;

    @GetMapping
    public ResponseEntity<List<NodeInfo>> listNodes(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        List<NodeInfo> nodes = nodeManagementService.getAllNodes();
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            nodes = nodes.stream().map(n -> {
                NodeInfo filtered = new NodeInfo();
                filtered.setNodeName(n.getNodeName());
                filtered.setReady(n.getReady());
                filtered.setRoles(n.getRoles());
                filtered.setCpuAllocatable(n.getCpuAllocatable());
                filtered.setMemoryAllocatable(n.getMemoryAllocatable());
                filtered.setGpuAllocatable(n.getGpuAllocatable());
                filtered.setCurrentTasks(n.getCurrentTasks());
                filtered.setMaxConcurrentTasks(n.getMaxConcurrentTasks());
                filtered.setCpuRemaining(n.getCpuRemaining());
                filtered.setMemRemainingMB(n.getMemRemainingMB());
                filtered.setGpuRemaining(n.getGpuRemaining());
                filtered.setWeightedScore(n.getWeightedScore());
                return filtered;
            }).collect(Collectors.toList());
        }
        return ResponseEntity.ok(nodes);
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getClusterOverview(HttpServletRequest httpRequest) {
        Map<String, Object> overview = nodeManagementService.getClusterOverview();
        return ResponseEntity.ok(overview);
    }

    @GetMapping("/k8s-status")
    public ResponseEntity<Map<String, Object>> getK8sStatus(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            Map<String, Object> limited = new HashMap<>();
            limited.put("ready", nodeManagementService.getK8sStatus().get("ready"));
            return ResponseEntity.ok(limited);
        }
        return ResponseEntity.ok(nodeManagementService.getK8sStatus());
    }

    @GetMapping("/{nodeName}")
    public ResponseEntity<Map<String, Object>> getNodeDetail(@PathVariable String nodeName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> detail = nodeManagementService.getNodeFullDetail(nodeName);
        if (detail.containsKey("error")) {
            return ResponseEntity.status(404).body(detail);
        }
        return ResponseEntity.ok(detail);
    }

    @PostMapping("/{nodeName}/cordon")
    public ResponseEntity<Map<String, String>> cordonNode(@PathVariable String nodeName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        try {
            nodeManagementService.toggleSchedulable(nodeName, false);
            authService.logOperation(null, username, "CONFIG_CHANGE", nodeName, "节点停止调度: " + nodeName);
            Map<String, String> response = new HashMap<>();
            response.put("message", "节点 " + nodeName + " 已停止调度");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/{nodeName}/uncordon")
    public ResponseEntity<Map<String, String>> uncordonNode(@PathVariable String nodeName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        try {
            nodeManagementService.toggleSchedulable(nodeName, true);
            authService.logOperation(null, username, "CONFIG_CHANGE", nodeName, "节点恢复调度: " + nodeName);
            Map<String, String> response = new HashMap<>();
            response.put("message", "节点 " + nodeName + " 已恢复调度");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @DeleteMapping("/{nodeName}")
    public ResponseEntity<Map<String, String>> deleteNode(@PathVariable String nodeName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        if (!"ROOT".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        boolean removed = nodeManagementService.deleteNode(nodeName);
        if (removed) {
            authService.logOperation(null, username, "CONFIG_CHANGE", nodeName, "删除节点记录: " + nodeName);
            Map<String, String> response = new HashMap<>();
            response.put("message", "节点记录已删除");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncNodes(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> syncResult = nodeManagementService.syncNodesFromCluster();
        authService.logOperation(null, username, "CONFIG_CHANGE", "nodes", "手动同步集群节点");
        return ResponseEntity.ok(syncResult);
    }

    @PostMapping("/{nodeName}/max-concurrent")
    public ResponseEntity<Map<String, String>> setNodeMaxConcurrent(
            @PathVariable String nodeName,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        Integer maxConcurrent = ((Number) request.get("maxConcurrent")).intValue();
        if (maxConcurrent < 1) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "并发数不能为0，如需停止调度请使用停止调度按钮");
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            nodeManagementService.setNodeMaxConcurrent(nodeName, maxConcurrent);
            authService.logOperation(null, username, "CONFIG_CHANGE", nodeName,
                    "节点 " + nodeName + " 最大并发数设为: " + maxConcurrent);
            Map<String, String> response = new HashMap<>();
            response.put("message", "节点 " + nodeName + " 最大并发数已设为 " + maxConcurrent);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/select-for-training")
    public ResponseEntity<Map<String, Object>> selectNodeForTraining(
            @RequestParam(required = false) String gpuType,
            @RequestParam(required = false) Integer gpuCount,
            HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role) && !"USER".equals(role)) {
            return ResponseEntity.status(403).build();
        }

        Map<String, String> gpuReqs = new HashMap<>();
        if (gpuType != null && gpuCount != null && gpuCount > 0) {
            gpuReqs.put(gpuType, String.valueOf(gpuCount));
        }

        NodeInfo selected = nodeManagementService.selectBestNodeForTraining(gpuReqs.isEmpty() ? null : gpuReqs);
        Map<String, Object> response = new HashMap<>();
        if (selected != null) {
            response.put("nodeName", selected.getNodeName());
            response.put("nodeIp", selected.getNodeIp());
            response.put("gpuAvailable", selected.getGpuAllocatable());
            response.put("cpuAllocatable", selected.getCpuAllocatable());
            response.put("status", "success");
        } else {
            response.put("message", "没有可用的节点满足训练需求");
            response.put("status", "no_available_node");
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/system-pods")
    public ResponseEntity<List<Map<String, Object>>> getSystemPods(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(k8sClientService.getSystemPods());
    }

    @GetMapping("/logs")
    public ResponseEntity<List<NodeLog>> getAllNodeLogs(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(nodeManagementService.getAllNodeLogs());
    }

    @GetMapping("/{nodeName}/logs")
    public ResponseEntity<List<NodeLog>> getNodeLogs(@PathVariable String nodeName, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(nodeManagementService.getNodeLogs(nodeName));
    }

    @GetMapping("/runs/{recordName}/{type}")
    public ResponseEntity<Map<String, Object>> getRunsResult(
            @PathVariable String recordName,
            @PathVariable String type,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = new HashMap<>();
        String projectRoot = yoloService.getProjectRoot();
        String runsDir = projectRoot + "runs/detect/" + recordName + "_" + type;

        File dir = new File(runsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            result.put("exists", false);
            result.put("message", "结果目录不存在");
            return ResponseEntity.ok(result);
        }

        result.put("exists", true);
        result.put("directory", runsDir);

        List<Map<String, Object>> files = new ArrayList<>();
        collectFiles(dir, "", files);
        result.put("files", files);

        File resultsCsv = new File(runsDir, "results.csv");
        if (resultsCsv.exists()) {
            try {
                String content = Files.readString(resultsCsv.toPath());
                result.put("resultsCsv", content);
            } catch (Exception e) {
                result.put("resultsCsvError", e.getMessage());
            }
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/runs/{recordName}/{type}/file")
    public ResponseEntity<Map<String, Object>> getRunsFileContent(
            @PathVariable String recordName,
            @PathVariable String type,
            @RequestParam String path,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = new HashMap<>();
        String projectRoot = yoloService.getProjectRoot();
        String basePath = projectRoot + "runs/detect/" + recordName + "_" + type;
        String filePath = basePath + "/" + path;

        try {
            File file = new File(filePath).getCanonicalFile();
            File baseDir = new File(basePath).getCanonicalFile();
            if (!file.getPath().startsWith(baseDir.getPath())) {
                result.put("error", "路径不合法");
                return ResponseEntity.status(403).body(result);
            }
            if (!file.exists() || !file.isFile()) {
                result.put("error", "文件不存在");
                return ResponseEntity.status(404).body(result);
            }
            String content = Files.readString(file.toPath());
            result.put("content", content);
            result.put("fileName", file.getName());
            result.put("size", file.length());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    private void collectFiles(File dir, String prefix, List<Map<String, Object>> files) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String path = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            Map<String, Object> info = new HashMap<>();
            info.put("name", child.getName());
            info.put("path", path);
            info.put("isDirectory", child.isDirectory());
            info.put("size", child.length());
            if (child.isFile()) {
                String name = child.getName().toLowerCase();
                info.put("isImage", name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"));
                info.put("isCsv", name.endsWith(".csv"));
                info.put("isText", name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml"));
            }
            files.add(info);
            if (child.isDirectory()) {
                collectFiles(child, path, files);
            }
        }
    }

    @GetMapping("/runs/{recordName}/{type}/image")
    public void getRunsImage(
            @PathVariable String recordName,
            @PathVariable String type,
            @RequestParam String path,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String projectRoot = yoloService.getProjectRoot();
        String basePath = projectRoot + "runs/detect/" + recordName + "_" + type;
        String filePath = basePath + "/" + path;

        try {
            File file = new File(filePath).getCanonicalFile();
            File baseDir = new File(basePath).getCanonicalFile();
            if (!file.getPath().startsWith(baseDir.getPath()) || !file.exists() || !file.isFile()) {
                httpResponse.setStatus(404);
                return;
            }

            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) contentType = "application/octet-stream";
            httpResponse.setContentType(contentType);
            httpResponse.setContentLengthLong(file.length());

            try (InputStream is = new FileInputStream(file); OutputStream os = httpResponse.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            try { httpResponse.setStatus(500); } catch (Exception ex) {}
        }
    }
}
