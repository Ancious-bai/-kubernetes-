package com.example.yoloproject.service;

import com.example.yoloproject.entity.NodeInfo;
import com.example.yoloproject.entity.NodeLog;
import com.example.yoloproject.repository.NodeInfoRepository;
import com.example.yoloproject.repository.NodeLogRepository;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1NodeCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NodeManagementService {

    private static final Logger log = LoggerFactory.getLogger(NodeManagementService.class);

    @Autowired
    private NodeInfoRepository nodeInfoRepository;

    @Autowired
    private K8sClientService k8sClientService;

    @Autowired
    private NodeLogRepository nodeLogRepository;

    public List<NodeInfo> getAllNodes() {
        List<NodeInfo> nodes = nodeInfoRepository.findAll();
        if (k8sClientService.isReady()) {
            for (NodeInfo node : nodes) {
                try {
                    int trainingPods = k8sClientService.getTrainingPodCountOnNode(node.getNodeName());
                    node.setCurrentTasks(trainingPods);
                } catch (Exception e) {
                    node.setCurrentTasks(0);
                }
            }
        }
        return nodes;
    }

    public List<NodeInfo> getReadyNodes() {
        return nodeInfoRepository.findByReadyTrue();
    }

    public List<NodeInfo> getSchedulableNodes() {
        return nodeInfoRepository.findBySchedulableTrue();
    }

    public Optional<NodeInfo> getNodeByName(String nodeName) {
        return nodeInfoRepository.findByNodeName(nodeName);
    }

    public NodeInfo selectBestNodeForTraining(Map<String, String> gpuRequirements) {
        List<NodeInfo> candidates = getSchedulableNodes().stream()
                .filter(NodeInfo::getReady)
                .collect(Collectors.toList());

        if (gpuRequirements != null && !gpuRequirements.isEmpty()) {
            candidates = candidates.stream()
                    .filter(n -> n.getGpuAllocatable() != null && !n.getGpuAllocatable().equals("0"))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.stream()
                .max(Comparator.comparingDouble(this::calculateNodeScoreForSelection))
                .orElse(candidates.get(0));
    }

    private double calculateNodeScoreForSelection(NodeInfo node) {
        double score = 0;

        try {
            Map<String, Object> allocated = k8sClientService.getNodeAllocatedResources(node.getNodeName());
            double cpuRequested = ((Number) allocated.getOrDefault("cpuRequested", 0)).doubleValue();
            double memoryRequestedMB = ((Number) allocated.getOrDefault("memoryRequestedMB", 0)).doubleValue();
            double gpuRequested = ((Number) allocated.getOrDefault("gpuRequested", 0)).doubleValue();
            int runningPods = ((Number) allocated.getOrDefault("runningPods", 0)).intValue();

            double cpuTotal = parseCpuCores(node.getCpuAllocatable());
            double memTotalMB = parseMemoryMB(node.getMemoryAllocatable());
            double gpuTotal = parseGpuCount(node.getGpuAllocatable());
            int maxConcurrent = node.getMaxConcurrentTasks() != null ? node.getMaxConcurrentTasks() : 1;

            double cpuRemaining = Math.max(0, cpuTotal - cpuRequested);
            double memRemainingMB = Math.max(0, memTotalMB - memoryRequestedMB);
            double gpuRemaining = Math.max(0, gpuTotal - gpuRequested);
            int remainingSlots = Math.max(0, maxConcurrent - runningPods);

            score += remainingSlots * 30;
            score += cpuRemaining * 10;
            score += (memRemainingMB / 1024.0) * 5;
            score += gpuRemaining * 50;
        } catch (Exception e) {
            log.warn("Failed to calculate score for node {}: {}", node.getNodeName(), e.getMessage());
            score = 0;
        }

        return score;
    }

    @Scheduled(fixedDelay = 30000)
    public void syncNodesFromCluster() {
        if (!k8sClientService.isReady()) {
            return;
        }

        try {
            List<Map<String, Object>> clusterNodes = k8sClientService.getNodeInfoList();
            Set<String> clusterNodeNames = new HashSet<>();

            for (Map<String, Object> nodeInfo : clusterNodes) {
                String nodeName = (String) nodeInfo.get("name");
                clusterNodeNames.add(nodeName);

                NodeInfo dbNode = nodeInfoRepository.findByNodeName(nodeName).orElse(new NodeInfo(nodeName));
                updateNodeFromCluster(dbNode, nodeInfo);
                calculateDynamicConcurrent(dbNode);
                nodeInfoRepository.save(dbNode);
            }

            List<NodeInfo> dbNodes = nodeInfoRepository.findAll();
            for (NodeInfo dbNode : dbNodes) {
                if (!clusterNodeNames.contains(dbNode.getNodeName())) {
                    nodeInfoRepository.delete(dbNode);
                    log.info("Removed node {} from database (no longer in cluster)", dbNode.getNodeName());
                }
            }

            log.debug("Node sync completed, {} nodes from cluster", clusterNodeNames.size());
        } catch (Exception e) {
            log.error("Failed to sync nodes from cluster: {}", e.getMessage());
        }
    }

    private void updateNodeFromCluster(NodeInfo dbNode, Map<String, Object> clusterInfo) {
        dbNode.setNodeIp((String) clusterInfo.get("ip"));
        dbNode.setReady((Boolean) clusterInfo.get("ready"));

        Object roles = clusterInfo.get("roles");
        if (roles instanceof List) {
            dbNode.setRoles(String.join(",", (List<String>) roles));
        } else if (roles instanceof String) {
            dbNode.setRoles((String) roles);
        }

        boolean k8sSchedulable = !(Boolean) clusterInfo.getOrDefault("unschedulable", false);
        if (dbNode.getId() == null) {
            dbNode.setSchedulable(k8sSchedulable);
        } else if (!dbNode.getSchedulable() && k8sSchedulable) {
            try {
                k8sClientService.cordonNode(dbNode.getNodeName(), true);
            } catch (Exception e) {
                log.warn("Re-cordon node {} failed: {}", dbNode.getNodeName(), e.getMessage());
            }
        } else {
            dbNode.setSchedulable(k8sSchedulable);
        }

        Map<String, String> capacity = (Map<String, String>) clusterInfo.get("capacity");
        if (capacity != null) {
            dbNode.setCpuCapacity(capacity.get("cpu"));
            dbNode.setMemoryCapacity(capacity.get("memory"));
            String gpu = extractGpuFromResourceMap(capacity);
            dbNode.setGpuCapacity(gpu);
        }

        Map<String, String> allocatable = (Map<String, String>) clusterInfo.get("allocatable");
        if (allocatable != null) {
            dbNode.setCpuAllocatable(allocatable.get("cpu"));
            dbNode.setMemoryAllocatable(allocatable.get("memory"));
            String gpu = extractGpuFromResourceMap(allocatable);
            dbNode.setGpuAllocatable(gpu);
        }

        dbNode.setKubeletVersion((String) clusterInfo.get("kubeletVersion"));

        Map<String, String> labels = (Map<String, String>) clusterInfo.get("labels");
        if (labels != null) {
            dbNode.setLabels(labels.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(",")));
        }

        dbNode.setLastHeartbeat(LocalDateTime.now());
    }

    private String extractGpuFromResourceMap(Map<String, String> resourceMap) {
        for (Map.Entry<String, String> entry : resourceMap.entrySet()) {
            if (entry.getKey().contains("nvidia.com") || entry.getKey().contains("gpu")) {
                return entry.getValue();
            }
        }
        return "0";
    }

    private void calculateDynamicConcurrent(NodeInfo node) {
        int cpuCores = parseCpuCores(node.getCpuAllocatable());
        long memoryMB = parseMemoryMB(node.getMemoryAllocatable());
        boolean isMaster = node.getRoles() != null &&
                (node.getRoles().contains("control-plane") || node.getRoles().contains("master"));
        boolean hasGpu = node.getGpuAllocatable() != null && !"0".equals(node.getGpuAllocatable());

        int systemReservedCores = isMaster ? 2 : 1;
        long systemReservedMB = isMaster ? 2048 : 1024;

        int availableCores = Math.max(1, cpuCores - systemReservedCores);
        long availableMB = Math.max(512, memoryMB - systemReservedMB);

        int coresBasedConcurrent = Math.max(1, availableCores);
        int memoryBasedConcurrent = Math.max(1, (int) (availableMB / 1024));

        int concurrent = Math.min(coresBasedConcurrent, memoryBasedConcurrent);

        if (isMaster) {
            concurrent = Math.min(concurrent, 1);
        }

        if (hasGpu) {
            int gpuCount = parseGpuCount(node.getGpuAllocatable());
            if (gpuCount > 0) {
                concurrent = Math.min(concurrent, Math.max(1, gpuCount));
            }
        }

        concurrent = Math.max(1, concurrent);

        int oldConcurrent = node.getMaxConcurrentTasks() != null ? node.getMaxConcurrentTasks() : 1;
        if (oldConcurrent != concurrent) {
            log.info("Dynamic concurrent for node {}: {} -> {} (cpuCores={}, memMB={}, isMaster={}, hasGpu={})",
                    node.getNodeName(), oldConcurrent, concurrent, cpuCores, memoryMB, isMaster, hasGpu);
        }
        node.setMaxConcurrentTasks(concurrent);
    }

    private int parseCpuCores(String cpuStr) {
        if (cpuStr == null || cpuStr.isEmpty()) return 2;
        try {
            String s = cpuStr.trim();
            if (s.endsWith("m")) {
                return Math.max(1, Integer.parseInt(s.substring(0, s.length() - 1)) / 1000);
            }
            double val = Double.parseDouble(s);
            return Math.max(1, (int) val);
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private long parseMemoryMB(String memStr) {
        if (memStr == null || memStr.isEmpty()) return 4096;
        try {
            String s = memStr.trim();
            if (s.endsWith("Ki")) {
                return Long.parseLong(s.substring(0, s.length() - 2)) / 1024;
            } else if (s.endsWith("Mi")) {
                return Long.parseLong(s.substring(0, s.length() - 2));
            } else if (s.endsWith("Gi")) {
                return Long.parseLong(s.substring(0, s.length() - 2)) * 1024;
            } else if (s.endsWith("K") || s.endsWith("k")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) / 1000;
            } else if (s.endsWith("M") || s.endsWith("m")) {
                return Long.parseLong(s.substring(0, s.length() - 1));
            } else if (s.endsWith("G") || s.endsWith("g")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 1000;
            }
            return Long.parseLong(s) / (1024 * 1024);
        } catch (NumberFormatException e) {
            return 4096;
        }
    }

    private int parseGpuCount(String gpuStr) {
        if (gpuStr == null || gpuStr.isEmpty()) return 0;
        try {
            return Integer.parseInt(gpuStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Map<String, Object> getNodeDetailedResources(String nodeName) {
        Map<String, Object> result = new HashMap<>();
        Optional<NodeInfo> nodeOpt = nodeInfoRepository.findByNodeName(nodeName);
        if (nodeOpt.isEmpty()) {
            result.put("error", "Node not found: " + nodeName);
            return result;
        }

        NodeInfo node = nodeOpt.get();
        result.put("nodeName", node.getNodeName());
        result.put("ip", node.getNodeIp());
        result.put("ready", node.getReady());
        result.put("roles", node.getRoles());
        result.put("cpuCapacity", node.getCpuCapacity());
        result.put("cpuAllocatable", node.getCpuAllocatable());
        result.put("memoryCapacity", node.getMemoryCapacity());
        result.put("memoryAllocatable", node.getMemoryAllocatable());
        result.put("gpuCapacity", node.getGpuCapacity());
        result.put("gpuAllocatable", node.getGpuAllocatable());
        result.put("schedulable", node.getSchedulable());
        result.put("lastHeartbeat", node.getLastHeartbeat());

        if (k8sClientService.isReady()) {
            Map<String, Object> allocated = k8sClientService.getNodeAllocatedResources(nodeName);
            result.put("cpuRequested", allocated.get("cpuRequested"));
            result.put("memoryRequestedMB", allocated.get("memoryRequestedMB"));
            result.put("gpuRequested", allocated.get("gpuRequested"));
            result.put("runningPods", allocated.get("runningPods"));
        }

        return result;
    }

    public NodeInfo toggleSchedulable(String nodeName, boolean schedulable) {
        NodeInfo node = nodeInfoRepository.findByNodeName(nodeName).orElse(null);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nodeName);
        }
        try {
            k8sClientService.cordonNode(nodeName, !schedulable);
        } catch (Exception e) {
            log.warn("Failed to cordon/uncordon node {} in K8s: {}", nodeName, e.getMessage());
        }
        node.setSchedulable(schedulable);
        return nodeInfoRepository.save(node);
    }

    public boolean deleteNode(String nodeName) {
        if (nodeInfoRepository.existsByNodeName(nodeName)) {
            nodeInfoRepository.deleteByNodeName(nodeName);
            return true;
        }
        return false;
    }

    public NodeInfo setNodeMaxConcurrent(String nodeName, int maxConcurrent) {
        NodeInfo node = nodeInfoRepository.findByNodeName(nodeName).orElse(null);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: " + nodeName);
        }
        node.setMaxConcurrentTasks(maxConcurrent);
        return nodeInfoRepository.save(node);
    }

    public Map<String, Object> getClusterOverview() {
        Map<String, Object> overview = new HashMap<>();
        List<NodeInfo> allNodes = nodeInfoRepository.findAll();
        List<NodeInfo> readyNodes = allNodes.stream().filter(n -> Boolean.TRUE.equals(n.getReady())).collect(Collectors.toList());

        overview.put("totalNodes", allNodes.size());
        overview.put("readyNodes", readyNodes.size());
        overview.put("gpuNodes", readyNodes.stream().filter(n -> n.getGpuAllocatable() != null && !"0".equals(n.getGpuAllocatable())).count());
        overview.put("masterNodes", readyNodes.stream().filter(n -> n.getRoles() != null && (n.getRoles().contains("control-plane") || n.getRoles().contains("master"))).count());
        overview.put("workerNodes", readyNodes.stream().filter(n -> n.getRoles() != null && n.getRoles().contains("worker") && !n.getRoles().contains("control-plane") && !n.getRoles().contains("master")).count());

        return overview;
    }

    public NodeLog recordNodeLog(String nodeName, String recordName, String dataName,
                                  String taskType, String status, Integer epochs, Integer imgsz,
                                  String createdBy) {
        NodeLog nodeLog = new NodeLog();
        nodeLog.setNodeName(nodeName);
        nodeLog.setRecordName(recordName);
        nodeLog.setDataName(dataName);
        nodeLog.setTaskType(taskType);
        nodeLog.setStatus(status);
        nodeLog.setEpochs(epochs);
        nodeLog.setImgsz(imgsz);
        nodeLog.setCreatedBy(createdBy);
        nodeLog.setStartedAt(LocalDateTime.now());
        return nodeLogRepository.save(nodeLog);
    }

    public void updateNodeLogFinished(String recordName, String status) {
        List<NodeLog> logs = nodeLogRepository.findByRecordName(recordName);
        for (NodeLog nodeLog : logs) {
            if (nodeLog.getFinishedAt() == null) {
                nodeLog.setFinishedAt(LocalDateTime.now());
                nodeLog.setStatus(status);
                nodeLogRepository.save(nodeLog);
            }
        }
    }

    public void updateNodeLogResources(String recordName, String cpuUsage, String memoryUsage, String gpuUsage) {
        List<NodeLog> logs = nodeLogRepository.findByRecordName(recordName);
        for (NodeLog nodeLog : logs) {
            if (nodeLog.getFinishedAt() == null) {
                nodeLog.setCpuUsage(cpuUsage);
                nodeLog.setMemoryUsage(memoryUsage);
                nodeLog.setGpuUsage(gpuUsage);
                nodeLogRepository.save(nodeLog);
            }
        }
    }

    public List<NodeLog> getNodeLogs(String nodeName) {
        return nodeLogRepository.findByNodeNameOrderByStartedAtDesc(nodeName);
    }

    public List<NodeLog> getAllNodeLogs() {
        return nodeLogRepository.findAllByOrderByStartedAtDesc();
    }
}
