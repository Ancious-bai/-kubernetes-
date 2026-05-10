package com.example.yoloproject.service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.Streams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class K8sClientService {

    private static final Logger log = LoggerFactory.getLogger(K8sClientService.class);

    public static String sanitizeK8sName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        String sanitized = name.toLowerCase()
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+|-+$", "");
        if (sanitized.isEmpty() || sanitized.equals("-")) sanitized = "job";
        if (sanitized.length() > 63) sanitized = sanitized.substring(0, 63);
        return sanitized.replaceAll("-+$", "");
    }

    private CoreV1Api coreApi;
    private BatchV1Api batchApi;

    @org.springframework.beans.factory.annotation.Value("${app.k8s-namespace:default}")
    private String configuredNamespace;

    private String namespace = "default";

    @PostConstruct
    public void init() {
        try {
            if (configuredNamespace != null && !configuredNamespace.isEmpty()) {
                this.namespace = configuredNamespace;
            }

            String nsFromEnv = System.getenv("K8S_NAMESPACE");
            if (nsFromEnv != null && !nsFromEnv.isEmpty()) {
                this.namespace = nsFromEnv;
            }

            try {
                String nsFile = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";
                java.io.File nsFileObj = new java.io.File(nsFile);
                if (nsFileObj.exists() && (nsFromEnv == null || nsFromEnv.isEmpty())) {
                    this.namespace = new String(java.nio.file.Files.readAllBytes(nsFileObj.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {}

            ApiClient client;
            try {
                client = ClientBuilder.cluster().build();
                log.info("K8s client: using in-cluster config");
            } catch (Exception e) {
                String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
                java.io.File kubeConfigFile = new java.io.File(kubeConfigPath);
                if (kubeConfigFile.exists()) {
                    client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigFile))).build();
                    log.info("K8s client: using kubeconfig from {}", kubeConfigPath);
                } else {
                    String windowsKubeConfig = System.getProperty("user.home") + "\\AppData\\Local\\Docker\\wsl\\data\\kubeconfig";
                    java.io.File winKubeConfigFile = new java.io.File(windowsKubeConfig);
                    if (winKubeConfigFile.exists()) {
                        client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(winKubeConfigFile))).build();
                        log.info("K8s client: using Docker Desktop kubeconfig");
                    } else {
                        client = ClientBuilder.defaultClient();
                        log.info("K8s client: using default config");
                    }
                }
            }
            client.setConnectTimeout(30000);
            client.setReadTimeout(120000);
            client.setWriteTimeout(30000);
            Configuration.setDefaultApiClient(client);
            coreApi = new CoreV1Api();
            batchApi = new BatchV1Api();
            log.info("K8s client initialized successfully, namespace: {}", namespace);
        } catch (Exception e) {
            log.error("Failed to initialize K8s client: {}", e.getMessage(), e);
        }
    }

    public boolean isReady() {
        return coreApi != null && batchApi != null;
    }

    public void setNamespace(String ns) {
        this.namespace = ns;
    }

    public String getNamespace() {
        return namespace;
    }

    public V1Job createJob(V1Job job) throws ApiException {
        try {
            return batchApi.createNamespacedJob(namespace, job).execute();
        } catch (ApiException e) {
            log.error("Failed to create job {}: {}", job.getMetadata().getName(), e.getResponseBody());
            throw e;
        }
    }

    public V1Job createJobFromYaml(String yamlContent) throws ApiException, IOException {
        V1Job job = io.kubernetes.client.util.Yaml.loadAs(yamlContent, V1Job.class);
        return createJob(job);
    }

    public boolean deleteJob(String jobName) {
        try {
            V1DeleteOptions deleteOptions = new V1DeleteOptions();
            deleteOptions.setPropagationPolicy("Background");
            batchApi.deleteNamespacedJob(jobName, namespace)
                    .body(deleteOptions)
                    .execute();
            log.info("Deleted job: {}", jobName);
            return true;
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.debug("Job not found (already deleted): {}", jobName);
                return true;
            }
            log.error("Failed to delete job {}: {}", jobName, e.getResponseBody());
            return false;
        }
    }

    public V1Job getJob(String jobName) throws ApiException {
        return batchApi.readNamespacedJob(jobName, namespace).execute();
    }

    public V1JobList listJobs(String labelSelector) throws ApiException {
        return batchApi.listNamespacedJob(namespace)
                .labelSelector(labelSelector)
                .execute();
    }

    public String getJobStatus(String jobName) {
        try {
            V1Job job = getJob(jobName);
            if (job == null || job.getStatus() == null) return "UNKNOWN";
            if (job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() > 0) return "Succeeded";
            if (job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0) return "Failed";
            if (job.getStatus().getActive() != null && job.getStatus().getActive() > 0) return "Running";
            if (job.getStatus().getStartTime() == null) return "Pending";
            return "Running";
        } catch (ApiException e) {
            if (e.getCode() == 404) return "NOT_FOUND";
            log.error("Failed to get job status for {}: {}", jobName, e.getMessage());
            return "ERROR";
        }
    }

    public V1PodList getPodsByJobName(String jobName) throws ApiException {
        return coreApi.listNamespacedPod(namespace)
                .labelSelector("job-name=" + jobName)
                .execute();
    }

    public String getFirstPodNameByJob(String jobName) {
        try {
            V1PodList pods = getPodsByJobName(jobName);
            if (pods != null && pods.getItems() != null && !pods.getItems().isEmpty()) {
                return pods.getItems().get(0).getMetadata().getName();
            }
        } catch (ApiException e) {
            log.error("Failed to get pods for job {}: {}", jobName, e.getMessage());
        }
        return null;
    }

    public String getPodPhase(String podName) {
        try {
            V1Pod pod = coreApi.readNamespacedPod(podName, namespace).execute();
            if (pod != null && pod.getStatus() != null) {
                return pod.getStatus().getPhase();
            }
        } catch (ApiException e) {
            if (e.getCode() == 404) return "NOT_FOUND";
            log.error("Failed to get pod phase for {}: {}", podName, e.getMessage());
        }
        return "UNKNOWN";
    }

    public String getPodLogs(String podName) {
        return getPodLogs(podName, null);
    }

    public String getPodLogs(String podName, Integer tailLines) {
        try {
            var callBuilder = coreApi.readNamespacedPodLog(podName, namespace);
            if (tailLines != null && tailLines > 0) {
                callBuilder.tailLines(tailLines);
            }
            return callBuilder.execute();
        } catch (ApiException e) {
            log.error("Failed to get pod logs for {}: {}", podName, e.getMessage());
            return "Error getting logs: " + e.getMessage();
        }
    }

    public String streamPodLogs(String podName) throws ApiException {
        return coreApi.readNamespacedPodLog(podName, namespace)
                .follow(true)
                .execute();
    }

    public boolean deletePodsByJob(String jobName) {
        try {
            V1DeleteOptions deleteOptions = new V1DeleteOptions();
            coreApi.deleteCollectionNamespacedPod(namespace)
                    .labelSelector("job-name=" + jobName)
                    .body(deleteOptions)
                    .execute();
            log.info("Deleted pods for job: {}", jobName);
            return true;
        } catch (ApiException e) {
            log.error("Failed to delete pods for job {}: {}", jobName, e.getMessage());
            return false;
        }
    }

    public V1NodeList listNodes() throws ApiException {
        return coreApi.listNode().execute();
    }

    public V1Node getNode(String nodeName) throws ApiException {
        return coreApi.readNode(nodeName).execute();
    }

    public List<Map<String, Object>> getNodeInfoList() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            V1NodeList nodes = listNodes();
            if (nodes == null || nodes.getItems() == null) return result;

            for (V1Node node : nodes.getItems()) {
                Map<String, Object> info = new HashMap<>();
                V1ObjectMeta metadata = node.getMetadata();
                V1NodeStatus status = node.getStatus();
                V1NodeSpec spec = node.getSpec();

                info.put("name", metadata.getName());
                info.put("labels", metadata.getLabels());

                for (V1NodeAddress addr : status.getAddresses()) {
                    if ("InternalIP".equals(addr.getType())) {
                        info.put("ip", addr.getAddress());
                    }
                }

                for (V1NodeCondition cond : status.getConditions()) {
                    if ("Ready".equals(cond.getType())) {
                        info.put("ready", "True".equals(cond.getStatus()));
                        info.put("readySince", cond.getLastTransitionTime());
                    }
                }

                Map<String, String> capacity = new HashMap<>();
                if (status.getCapacity() != null) {
                    status.getCapacity().forEach((k, v) -> capacity.put(k, v.toSuffixedString()));
                }
                info.put("capacity", capacity);

                Map<String, String> allocatable = new HashMap<>();
                if (status.getAllocatable() != null) {
                    status.getAllocatable().forEach((k, v) -> allocatable.put(k, v.toSuffixedString()));
                }
                info.put("allocatable", allocatable);

                info.put("roles", getNodeRoles(metadata.getLabels()));
                info.put("unschedulable", spec.getUnschedulable() != null && spec.getUnschedulable());
                info.put("kubeletVersion", status.getNodeInfo() != null ? status.getNodeInfo().getKubeletVersion() : "unknown");

                result.add(info);
            }
        } catch (ApiException e) {
            log.error("Failed to list nodes: {}", e.getMessage());
        }
        return result;
    }

    private List<String> getNodeRoles(Map<String, String> labels) {
        List<String> roles = new ArrayList<>();
        if (labels == null) return roles;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (entry.getKey().startsWith("node-role.kubernetes.io/") && "true".equals(entry.getValue())) {
                roles.add(entry.getKey().substring("node-role.kubernetes.io/".length()));
            }
        }
        if (labels.containsKey("kubernetes.io/role")) {
            roles.add(labels.get("kubernetes.io/role"));
        }
        if (roles.isEmpty()) {
            if (labels.containsKey("node-role.kubernetes.io/control-plane") ||
                labels.containsKey("node-role.kubernetes.io/master")) {
                // already handled above
            } else {
                roles.add("worker");
            }
        }
        return roles;
    }

    public Map<String, Object> getNodeAllocatedResources(String nodeName) {
        Map<String, Object> result = new HashMap<>();
        try {
            V1PodList pods = coreApi.listPodForAllNamespaces()
                    .fieldSelector("spec.nodeName=" + nodeName + ",status.phase!=Failed,status.phase!=Succeeded")
                    .execute();

            double cpuRequested = 0;
            double memoryRequested = 0;
            double gpuRequested = 0;

            if (pods != null && pods.getItems() != null) {
                for (V1Pod pod : pods.getItems()) {
                    if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
                        for (V1Container container : pod.getSpec().getContainers()) {
                            if (container.getResources() != null && container.getResources().getRequests() != null) {
                                for (Map.Entry<String, Quantity> entry : container.getResources().getRequests().entrySet()) {
                                    String key = entry.getKey();
                                    String value = entry.getValue().toSuffixedString();
                                    if ("cpu".equals(key)) {
                                        cpuRequested += parseCpu(value);
                                    } else if ("memory".equals(key)) {
                                        memoryRequested += parseMemory(value);
                                    } else if (key.contains("gpu") || key.contains("nvidia.com")) {
                                        gpuRequested += Double.parseDouble(value);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            result.put("cpuRequested", cpuRequested);
            result.put("memoryRequestedMB", memoryRequested);
            result.put("gpuRequested", gpuRequested);
            result.put("runningPods", pods != null && pods.getItems() != null ? pods.getItems().size() : 0);

        } catch (ApiException e) {
            log.error("Failed to get allocated resources for node {}: {}", nodeName, e.getMessage());
        }
        return result;
    }

    private double parseCpu(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            if (value.endsWith("m")) {
                return Double.parseDouble(value.substring(0, value.length() - 1)) / 1000.0;
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseMemory(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            if (value.endsWith("Ki")) {
                return Double.parseDouble(value.substring(0, value.length() - 2)) / 1024.0;
            }
            if (value.endsWith("Mi")) {
                return Double.parseDouble(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("Gi")) {
                return Double.parseDouble(value.substring(0, value.length() - 2)) * 1024;
            }
            return Double.parseDouble(value) / (1024 * 1024);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public V1Job buildPreprocessJob(String jobName, String dataName, String inputDir,
                                     String imageName, String pvcName, String mountPath) {
        V1Job job = new V1Job();

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(sanitizeK8sName(jobName));
        Map<String, String> labels = new HashMap<>();
        labels.put("site", dataName);
        labels.put("type", "preprocess");
        labels.put("app", "yolo-training");
        metadata.setLabels(labels);
        job.setMetadata(metadata);

        V1JobSpec jobSpec = new V1JobSpec();
        jobSpec.setParallelism(1);
        jobSpec.setCompletions(1);
        jobSpec.setBackoffLimit(2);
        jobSpec.setTtlSecondsAfterFinished(3600);

        V1PodTemplateSpec template = new V1PodTemplateSpec();
        V1ObjectMeta templateMeta = new V1ObjectMeta();
        templateMeta.setLabels(labels);
        template.setMetadata(templateMeta);

        V1PodSpec podSpec = new V1PodSpec();
        podSpec.setRestartPolicy("Never");

        V1Container container = new V1Container();
        container.setName("yolo-container");
        container.setImage(imageName);
        container.setImagePullPolicy("IfNotPresent");
        container.setWorkingDir("/app/workspace");

        String effectiveInputDir = inputDir;
        if (inputDir != null && inputDir.startsWith("/app/workspace/")) {
            effectiveInputDir = inputDir.replace("/app/workspace/", "/app/data/");
        } else if (inputDir != null && !inputDir.startsWith("/")) {
            effectiveInputDir = "/app/data/" + inputDir;
        } else if (inputDir != null && !inputDir.startsWith("/app/data")) {
            log.warn("Input dir {} is outside PVC mount /app/data/, prepending /app/data/", inputDir);
            effectiveInputDir = "/app/data/" + inputDir.replaceFirst("^/+", "");
        }

        List<String> command = Arrays.asList("python3", "/app/workspace/preprocess.py",
                "--input_dir", effectiveInputDir);
        container.setCommand(command);

        V1EnvVar envVar1 = new V1EnvVar();
        envVar1.setName("PYTHONUNBUFFERED");
        envVar1.setValue("1");
        V1EnvVar envVar2 = new V1EnvVar();
        envVar2.setName("DATA_ROOT");
        envVar2.setValue("/app/data");
        container.setEnv(Arrays.asList(envVar1, envVar2));

        if (pvcName != null && !pvcName.isEmpty()) {
            V1VolumeMount volumeMount = new V1VolumeMount();
            volumeMount.setName("app-volume");
            volumeMount.setMountPath("/app/data");
            container.setVolumeMounts(Collections.singletonList(volumeMount));
        }

        V1ResourceRequirements resources = new V1ResourceRequirements();
        Map<String, Quantity> requests = new HashMap<>();
        Map<String, Quantity> limits = new HashMap<>();
        requests.put("cpu", new Quantity("500m"));
        requests.put("memory", new Quantity("1Gi"));
        limits.put("cpu", new Quantity("2"));
        limits.put("memory", new Quantity("4Gi"));
        resources.setRequests(requests);
        resources.setLimits(limits);
        container.setResources(resources);

        podSpec.setContainers(Collections.singletonList(container));

        if (pvcName != null && !pvcName.isEmpty()) {
            V1Volume volume = new V1Volume();
            volume.setName("app-volume");
            V1PersistentVolumeClaimVolumeSource pvcSource = new V1PersistentVolumeClaimVolumeSource();
            pvcSource.setClaimName(pvcName);
            volume.setPersistentVolumeClaim(pvcSource);
            podSpec.setVolumes(Collections.singletonList(volume));
        }

        template.setSpec(podSpec);
        jobSpec.setTemplate(template);
        job.setSpec(jobSpec);

        return job;
    }

    public V1Job buildTrainingJob(String jobName, String dataName, String recordName,
                                   int epochs, int imgsz, String imageName,
                                   String pvcName, String mountPath,
                                   String nodeName, Map<String, String> nodeSelector,
                                   Map<String, String> gpuResources) {
        V1Job job = new V1Job();

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(sanitizeK8sName(jobName));
        Map<String, String> labels = new HashMap<>();
        labels.put("site", dataName);
        labels.put("type", "train");
        labels.put("app", "yolo-training");
        metadata.setLabels(labels);
        job.setMetadata(metadata);

        V1JobSpec jobSpec = new V1JobSpec();
        jobSpec.setParallelism(1);
        jobSpec.setCompletions(1);
        jobSpec.setBackoffLimit(2);
        jobSpec.setTtlSecondsAfterFinished(3600);

        V1PodTemplateSpec template = new V1PodTemplateSpec();
        V1ObjectMeta templateMeta = new V1ObjectMeta();
        templateMeta.setLabels(labels);
        template.setMetadata(templateMeta);

        V1PodSpec podSpec = new V1PodSpec();
        podSpec.setRestartPolicy("Never");

        if (nodeName != null && !nodeName.isEmpty()) {
            podSpec.setNodeName(nodeName);
        }
        if (nodeSelector != null && !nodeSelector.isEmpty()) {
            podSpec.setNodeSelector(nodeSelector);
        }

        V1Container container = new V1Container();
        container.setName("yolo-container");
        container.setImage(imageName);
        container.setImagePullPolicy("IfNotPresent");

        container.setWorkingDir("/app/workspace");

        List<String> command = Arrays.asList("python3", "/app/workspace/train_yolo.py",
                "--site", dataName + "_processed",
                "--name", recordName + "_train",
                "--epochs", String.valueOf(epochs),
                "--imgsz", String.valueOf(imgsz));
        container.setCommand(command);

        V1EnvVar trainEnv1 = new V1EnvVar();
        trainEnv1.setName("PYTHONUNBUFFERED");
        trainEnv1.setValue("1");
        V1EnvVar trainEnv2 = new V1EnvVar();
        trainEnv2.setName("DATA_ROOT");
        trainEnv2.setValue("/app/data");
        container.setEnv(Arrays.asList(trainEnv1, trainEnv2));

        if (pvcName != null && !pvcName.isEmpty()) {
            V1VolumeMount volumeMount = new V1VolumeMount();
            volumeMount.setName("app-volume");
            volumeMount.setMountPath("/app/data");
            container.setVolumeMounts(Collections.singletonList(volumeMount));
        }

        V1ResourceRequirements resources = new V1ResourceRequirements();
        Map<String, Quantity> requests = new HashMap<>();
        Map<String, Quantity> limits = new HashMap<>();

        requests.put("cpu", new Quantity("500m"));
        requests.put("memory", new Quantity("1Gi"));
        limits.put("cpu", new Quantity("2"));
        limits.put("memory", new Quantity("4Gi"));

        if (gpuResources != null && !gpuResources.isEmpty()) {
            for (Map.Entry<String, String> entry : gpuResources.entrySet()) {
                Quantity gpuQty = new Quantity(entry.getValue());
                requests.put(entry.getKey(), gpuQty);
                limits.put(entry.getKey(), gpuQty);
            }
        }

        resources.setRequests(requests);
        resources.setLimits(limits);
        container.setResources(resources);

        podSpec.setContainers(Collections.singletonList(container));

        if (pvcName != null && !pvcName.isEmpty()) {
            V1Volume volume = new V1Volume();
            volume.setName("app-volume");
            V1PersistentVolumeClaimVolumeSource pvcSource = new V1PersistentVolumeClaimVolumeSource();
            pvcSource.setClaimName(pvcName);
            volume.setPersistentVolumeClaim(pvcSource);
            podSpec.setVolumes(Collections.singletonList(volume));
        }

        template.setSpec(podSpec);
        jobSpec.setTemplate(template);
        job.setSpec(jobSpec);

        return job;
    }

    public V1Job buildTestJob(String jobName, String dataName, String recordName,
                               int imgsz, String imageName,
                               String pvcName, String mountPath,
                               String nodeName, Map<String, String> nodeSelector) {
        V1Job job = new V1Job();

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(sanitizeK8sName(jobName));
        Map<String, String> labels = new HashMap<>();
        labels.put("site", dataName);
        labels.put("type", "test");
        labels.put("app", "yolo-training");
        metadata.setLabels(labels);
        job.setMetadata(metadata);

        V1JobSpec jobSpec = new V1JobSpec();
        jobSpec.setParallelism(1);
        jobSpec.setCompletions(1);
        jobSpec.setBackoffLimit(2);
        jobSpec.setTtlSecondsAfterFinished(3600);

        V1PodTemplateSpec template = new V1PodTemplateSpec();
        V1ObjectMeta templateMeta = new V1ObjectMeta();
        templateMeta.setLabels(labels);
        template.setMetadata(templateMeta);

        V1PodSpec podSpec = new V1PodSpec();
        podSpec.setRestartPolicy("Never");

        if (nodeName != null && !nodeName.isEmpty()) {
            podSpec.setNodeName(nodeName);
        }
        if (nodeSelector != null && !nodeSelector.isEmpty()) {
            podSpec.setNodeSelector(nodeSelector);
        }

        V1Container container = new V1Container();
        container.setName("yolo-container");
        container.setImage(imageName);
        container.setImagePullPolicy("IfNotPresent");

        container.setWorkingDir("/app/workspace");

        List<String> command = Arrays.asList("python3", "/app/workspace/test_yolo.py",
                "--site", dataName + "_processed",
                "--name", recordName + "_train",
                "--imgsz", String.valueOf(imgsz));
        container.setCommand(command);

        V1EnvVar testEnv1 = new V1EnvVar();
        testEnv1.setName("PYTHONUNBUFFERED");
        testEnv1.setValue("1");
        V1EnvVar testEnv2 = new V1EnvVar();
        testEnv2.setName("DATA_ROOT");
        testEnv2.setValue("/app/data");
        container.setEnv(Arrays.asList(testEnv1, testEnv2));

        if (pvcName != null && !pvcName.isEmpty()) {
            V1VolumeMount volumeMount = new V1VolumeMount();
            volumeMount.setName("app-volume");
            volumeMount.setMountPath("/app/data");
            container.setVolumeMounts(Collections.singletonList(volumeMount));
        }

        V1ResourceRequirements resources = new V1ResourceRequirements();
        Map<String, Quantity> requests = new HashMap<>();
        Map<String, Quantity> limits = new HashMap<>();
        requests.put("cpu", new Quantity("500m"));
        requests.put("memory", new Quantity("1Gi"));
        limits.put("cpu", new Quantity("2"));
        limits.put("memory", new Quantity("4Gi"));
        resources.setRequests(requests);
        resources.setLimits(limits);
        container.setResources(resources);

        podSpec.setContainers(Collections.singletonList(container));

        if (pvcName != null && !pvcName.isEmpty()) {
            V1Volume volume = new V1Volume();
            volume.setName("app-volume");
            V1PersistentVolumeClaimVolumeSource pvcSource = new V1PersistentVolumeClaimVolumeSource();
            pvcSource.setClaimName(pvcName);
            volume.setPersistentVolumeClaim(pvcSource);
            podSpec.setVolumes(Collections.singletonList(volume));
        }

        template.setSpec(podSpec);
        jobSpec.setTemplate(template);
        job.setSpec(jobSpec);

        return job;
    }

    public V1PersistentVolumeClaim createPvc(String pvcName, String storageClassName, String storageRequest) throws ApiException {
        V1PersistentVolumeClaim pvc = new V1PersistentVolumeClaim();

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(pvcName);
        pvc.setMetadata(metadata);

        V1PersistentVolumeClaimSpec spec = new V1PersistentVolumeClaimSpec();
        spec.setAccessModes(Collections.singletonList("ReadWriteMany"));
        if (storageClassName != null && !storageClassName.isEmpty()) {
            spec.setStorageClassName(storageClassName);
        }
        Map<String, Quantity> requests = new HashMap<>();
        requests.put("storage", new Quantity(storageRequest));
        spec.setResources(new V1VolumeResourceRequirements().requests(requests));
        pvc.setSpec(spec);

        return coreApi.createNamespacedPersistentVolumeClaim(namespace, pvc).execute();
    }

    public boolean deletePvc(String pvcName) {
        try {
            coreApi.deleteNamespacedPersistentVolumeClaim(pvcName, namespace)
                    .body(new V1DeleteOptions())
                    .execute();
            return true;
        } catch (ApiException e) {
            log.error("Failed to delete PVC {}: {}", pvcName, e.getMessage());
            return false;
        }
    }

    public V1PodList listAllPods(String labelSelector) throws ApiException {
        return coreApi.listNamespacedPod(namespace)
                .labelSelector(labelSelector)
                .execute();
    }

    public String getPodNodeName(String podName) {
        try {
            V1Pod pod = coreApi.readNamespacedPod(podName, namespace).execute();
            if (pod != null && pod.getSpec() != null) {
                return pod.getSpec().getNodeName();
            }
        } catch (ApiException e) {
            log.error("Failed to get node name for pod {}: {}", podName, e.getMessage());
        }
        return null;
    }

    public void cordonNode(String nodeName, boolean unschedulable) throws ApiException {
        V1Node node = coreApi.readNode(nodeName).execute();
        if (node == null || node.getSpec() == null) return;
        node.getSpec().setUnschedulable(unschedulable);
        coreApi.patchNode(nodeName, node).execute();
    }
}
