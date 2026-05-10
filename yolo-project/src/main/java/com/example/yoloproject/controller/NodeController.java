package com.example.yoloproject.controller;

import com.example.yoloproject.entity.NodeInfo;
import com.example.yoloproject.service.AuthService;
import com.example.yoloproject.service.NodeManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/nodes")
public class NodeController {

    @Autowired
    private NodeManagementService nodeManagementService;

    @Autowired
    private AuthService authService;

    @GetMapping
    public ResponseEntity<List<NodeInfo>> listNodes(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        List<NodeInfo> nodes = nodeManagementService.getAllNodes();
        return ResponseEntity.ok(nodes);
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getClusterOverview(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> overview = nodeManagementService.getClusterOverview();
        return ResponseEntity.ok(overview);
    }

    @GetMapping("/{nodeName}")
    public ResponseEntity<Map<String, Object>> getNodeDetail(@PathVariable String nodeName, HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> detail = nodeManagementService.getNodeDetailedResources(nodeName);
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
    public ResponseEntity<Map<String, String>> syncNodes(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        if (!"ROOT".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).build();
        }
        nodeManagementService.syncNodesFromCluster();
        authService.logOperation(null, username, "CONFIG_CHANGE", "nodes", "手动同步集群节点");
        Map<String, String> response = new HashMap<>();
        response.put("message", "节点同步完成");
        response.put("status", "success");
        return ResponseEntity.ok(response);
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
        if (maxConcurrent < 1 || maxConcurrent > 10) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "并发数必须在1-10之间");
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
}
