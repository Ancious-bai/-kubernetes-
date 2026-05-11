package com.example.yoloproject.service;

import com.example.yoloproject.entity.NodeInfo;
import com.example.yoloproject.entity.NodeLog;
import com.example.yoloproject.repository.NodeInfoRepository;
import com.example.yoloproject.repository.NodeLogRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1NodeCondition;
import jakarta.annotation.PostConstruct;
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

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @PostConstruct
    public void init() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                log.info("Performing initial node sync on startup...");
                Map<String, Object> syncResult = syncNodesFromCluster();
                if (syncResult != null) {
                    log.info("Initial node sync result: status={}, syncedCount={}",
                            syncResult.get("status"), syncResult.get("syncedCount"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public Map<String, Object> getK8sStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("ready", k8sClientService.isReady());
        status.put("coreApiStatus", k8sClientService.getCoreApiStatus());
        status.put("batchApiStatus", k8sClientService.getBatchApiStatus());
        return status;
    }

    public List<NodeInfo> getAllNodes() {
        List<NodeInfo> nodes = nodeInfoRepository.findAll();
        if (k8sClientService.isReady()) {
            for (NodeInfo node : nodes) {
                try {
                    int trainingPods = k8sClientService.getTrainingPodCountOnNode(node.getNodeName());
                    node.setCurrentTasks(trainingPods);

                    Map<String, Object> allocated = k8sClientService.getNodeAllocatedResources(node.getNodeName());
                    double cpuUsed = ((Number) allocated.getOrDefault("cpuRequested", 0)).doubleValue();
                    double memUsedMB = ((Number) allocated.getOrDefault("memoryRequestedMB", 0)).doubleValue();
                    double gpuUsed = ((Number) allocated.getOrDefault("gpuRequested", 0)).doubleValue();

                    double cpuTotal = parseCpuCores(node.getCpuAllocatable());
                    long memTotalMB = parseMemoryMB(node.getMemoryAllocatable());
                    double gpuTotal = parseGpuCount(node.getGpuAllocatable());

                    node.setCpuRemaining(Math.max(0, cpuTotal - cpuUsed));
                    node.setMemRemainingMB(Math.max(0, memTotalMB - memUsedMB));
                    node.setGpuRemaining(Math.max(0, gpuTotal - gpuUsed));

                    double weightedScore = Math.max(0, cpuTotal - cpuUsed) * 10
                            + Math.max(0, memTotalMB - memUsedMB) / 1024.0 * 5
                            + Math.max(0, gpuTotal - gpuUsed) * 50;
                    node.setWeightedScore(weightedScore);
                } catch (Exception e) {
                    log.warn("Failed to get resources for node {}: {}", node.getNodeName(), e.getMessage());
                    node.setCurrentTasks(0);
                    setDefaultRemainingResources(node);
                }
            }
        } else {
            for (NodeInfo node : nodes) {
                node.setCurrentTasks(0);
                setDefaultRemainingResources(node);
            }
        }
        return nodes;
    }

    private void setDefaultRemainingResources(NodeInfo node) {
        double cpuTotal = parseCpuCores(node.getCpuAllocatable());
        long memTotalMB = parseMemoryMB(node.getMemoryAllocatable());
        double gpuTotal = parseGpuCount(node.getGpuAllocatable());
        if (node.getCpuRemaining() == null) node.setCpuRemaining(cpuTotal);
        if (node.getMemRemainingMB() == null) node.setMemRemainingMB((double) memTotalMB);
        if (node.getGpuRemaining() == null) node.setGpuRemaining(gpuTotal);
        if (node.getWeightedScore() == null) node.setWeightedScore(cpuTotal * 10 + memTotalMB / 1024.0 * 5 + gpuTotal * 50);
    }

    public List<NodeInfo> getReadyNodes() {
        return nodeInfoRepository.findByReadyTrue();
    }

    public List<NodeInfo> getSchedulableNodes() {
        return nodeInfoRepository.findBySchedulableTrue().stream()
                .filter(n -> !isMasterNode(n.getNodeName()))
                .collect(Collectors.toList());
    }

    private boolean isMasterNode(String nodeName) {
        return nodeName != null && (nodeName.equalsIgnoreCase("master") || 
                nodeName.equalsIgnoreCase("control-plane") ||
                nodeName.contains("-master") || nodeName.contains("master-"));
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
    public Map<String, Object> syncNodesFromCluster() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");

        if (!k8sClientService.isReady()) {
            log.warn("Node sync skipped: K8s client is not ready (coreApi={}, batchApi={})",
                    k8sClientService.getCoreApiStatus(), k8sClientService.getBatchApiStatus());
            result.put("status", "error");
            result.put("message", "K8s客户端未就绪，无法同步节点");
            result.put("syncedCount", 0);
            return result;
        }

        try {
            List<Map<String, Object>> clusterNodes = k8sClientService.getNodeInfoList();
            log.info("Got {} nodes from K8s cluster", clusterNodes.size());
            Set<String> clusterNodeNames = new HashSet<>();

            for (Map<String, Object> nodeInfo : clusterNodes) {
                String nodeName = (String) nodeInfo.get("name");
                clusterNodeNames.add(nodeName);

                NodeInfo dbNode = nodeInfoRepository.findByNodeName(nodeName).orElse(new NodeInfo(nodeName));
                updateNodeFromCluster(dbNode, nodeInfo);
                calculateDynamicConcurrent(dbNode);
                nodeInfoRepository.save(dbNode);
                log.info("Synced node: {} (ready={}, roles={})", nodeName, dbNode.getReady(), dbNode.getRoles());
            }

            List<NodeInfo> dbNodes = nodeInfoRepository.findAll();
            for (NodeInfo dbNode : dbNodes) {
                if (!clusterNodeNames.contains(dbNode.getNodeName())) {
                    nodeInfoRepository.delete(dbNode);
                    log.info("Removed node {} from database (no longer in cluster)", dbNode.getNodeName());
                }
            }

            log.info("Node sync completed, {} nodes from cluster", clusterNodeNames.size());
            result.put("message", "同步完成，共" + clusterNodeNames.size() + "个节点");
            result.put("syncedCount", clusterNodeNames.size());
            result.put("nodes", clusterNodeNames);
        } catch (Exception e) {
            log.error("Failed to sync nodes from cluster: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("message", "同步失败: " + e.getMessage());
            result.put("syncedCount", 0);
        }
        return result;
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

        boolean k8sSchedulable = !Boolean.TRUE.equals(clusterInfo.get("unschedulable"));
        boolean isControlPlane = dbNode.getRoles() != null &&
                (dbNode.getRoles().contains("control-plane") || dbNode.getRoles().contains("master"));
        dbNode.setSchedulable(k8sSchedulable && !isControlPlane);

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
            String labelsStr = labels.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("node-role.kubernetes.io/") || e.getKey().equals("kubernetes.io/role"))
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));
            dbNode.setLabels(labelsStr);
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
        double cpuCores = parseCpuCores(node.getCpuAllocatable());
        long memoryMB = parseMemoryMB(node.getMemoryAllocatable());
        boolean isMaster = node.getRoles() != null &&
                (node.getRoles().contains("control-plane") || node.getRoles().contains("master"));
        boolean hasGpu = node.getGpuAllocatable() != null && !"0".equals(node.getGpuAllocatable());

        int systemReservedCores = isMaster ? 2 : 1;
        long systemReservedMB = isMaster ? 2048 : 1024;

        int availableCores = Math.max(1, (int) cpuCores - systemReservedCores);
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

    private double parseCpuCores(String cpuStr) {
        if (cpuStr == null || cpuStr.isEmpty()) return 2.0;
        try {
            String s = cpuStr.trim();
            if (s.endsWith("m")) {
                return Double.parseDouble(s.substring(0, s.length() - 1)) / 1000.0;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 2.0;
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

            double cpuTotal = parseCpuCores(node.getCpuAllocatable());
            long memTotalMB = parseMemoryMB(node.getMemoryAllocatable());
            double cpuUsed = ((Number) allocated.getOrDefault("cpuRequested", 0)).doubleValue();
            double memUsedMB = ((Number) allocated.getOrDefault("memoryRequestedMB", 0)).doubleValue();
            double gpuTotal = parseGpuCount(node.getGpuAllocatable());
            double gpuUsed = ((Number) allocated.getOrDefault("gpuRequested", 0)).doubleValue();

            result.put("cpuTotalCores", cpuTotal);
            result.put("memTotalMB", memTotalMB);
            result.put("gpuTotal", gpuTotal);
            result.put("cpuRemaining", Math.max(0, cpuTotal - cpuUsed));
            result.put("memRemainingMB", Math.max(0, memTotalMB - memUsedMB));
            result.put("gpuRemaining", Math.max(0, gpuTotal - gpuUsed));

            double weightedScore = Math.max(0, cpuTotal - cpuUsed) * 10
                    + Math.max(0, memTotalMB - memUsedMB) / 1024.0 * 5
                    + Math.max(0, gpuTotal - gpuUsed) * 50;
            result.put("weightedResourceScore", String.format("%.1f", weightedScore));
        }

        return result;
    }

    public Map<String, Object> getNodeFullDetail(String nodeName) {
        Map<String, Object> result = getNodeDetailedResources(nodeName);
        if (result.containsKey("error")) return result;

        if (k8sClientService.isReady()) {
            List<Map<String, Object>> pods = k8sClientService.getNodePodDetails(nodeName);
            result.put("pods", pods);

            List<Map<String, Object>> trainingPods = pods.stream()
                    .filter(p -> Boolean.TRUE.equals(p.get("isTrainingPod")))
                    .collect(Collectors.toList());

            for (Map<String, Object> pod : trainingPods) {
                String jobName = (String) pod.getOrDefault("jobName", "");
                if (!jobName.isEmpty()) {
                    String recordName = jobName.replaceAll("-train-job$", "").replaceAll("-test-job$", "").replaceAll("-preprocess-job$", "");
                    pod.put("recordName", recordName);
                    try {
                        com.example.yoloproject.entity.TrainingRecord record =
                                trainingRecordRepository != null ?
                                trainingRecordRepository.findByRecordName(recordName).orElse(null) : null;
                        if (record != null) {
                            pod.put("createdBy", record.getCreatedBy());
                            pod.put("epochs", record.getEpochs());
                            pod.put("imgsz", record.getImgsz());
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            result.put("trainingPods", trainingPods);
            result.put("trainingPodCount", trainingPods.size());
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
