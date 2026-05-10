package com.example.yoloproject.service;

import com.example.yoloproject.entity.NodeInfo;
import com.example.yoloproject.repository.NodeInfoRepository;
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

    public List<NodeInfo> getAllNodes() {
        List<NodeInfo> nodes = nodeInfoRepository.findAll();
        if (k8sClientService.isReady()) {
            for (NodeInfo node : nodes) {
                try {
                    Map<String, Object> allocated = k8sClientService.getNodeAllocatedResources(node.getNodeName());
                    int runningPods = (int) allocated.getOrDefault("runningPods", 0);
                    node.setCurrentTasks(runningPods);
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
                .min(Comparator.comparingInt(this::getCurrentTrainingLoad))
                .orElse(candidates.get(0));
    }

    private int getCurrentTrainingLoad(NodeInfo node) {
        try {
            Map<String, Object> allocated = k8sClientService.getNodeAllocatedResources(node.getNodeName());
            return (int) allocated.getOrDefault("runningPods", 0);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
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
                nodeInfoRepository.save(dbNode);
            }

            List<NodeInfo> dbNodes = nodeInfoRepository.findAll();
            for (NodeInfo dbNode : dbNodes) {
                if (!clusterNodeNames.contains(dbNode.getNodeName())) {
                    dbNode.setReady(false);
                    dbNode.setLastHeartbeat(LocalDateTime.now());
                    nodeInfoRepository.save(dbNode);
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
}
