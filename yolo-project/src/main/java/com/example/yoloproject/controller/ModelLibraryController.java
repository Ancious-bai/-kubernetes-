package com.example.yoloproject.controller;

import com.example.yoloproject.entity.Dataset;
import com.example.yoloproject.entity.InferenceRecord;
import com.example.yoloproject.entity.ModelLibrary;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.DatasetRepository;
import com.example.yoloproject.repository.InferenceRecordRepository;
import com.example.yoloproject.repository.ModelLibraryRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import com.example.yoloproject.service.AuthService;
import com.example.yoloproject.service.K8sClientService;
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
@RequestMapping("/api/models")
public class ModelLibraryController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelLibraryController.class);

    @Autowired
    private ModelLibraryRepository modelLibraryRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private InferenceRecordRepository inferenceRecordRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private YoloService yoloService;

    @Autowired
    private AuthService authService;

    @Autowired
    private com.example.yoloproject.service.K8sClientService k8sClientService;

    @GetMapping
    public ResponseEntity<List<ModelLibrary>> listModels(HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        List<ModelLibrary> models;
        if ("ROOT".equals(role)) {
            models = modelLibraryRepository.findAllByOrderByCreatedAtDesc();
        } else if ("ADMIN".equals(role)) {
            List<String> visibleUsers = authService.getUsersForAdmin(username).stream()
                    .map(u -> u.getUsername()).collect(Collectors.toList());
            visibleUsers.add(username);
            models = modelLibraryRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(m -> visibleUsers.contains(m.getCreatedBy()))
                    .collect(Collectors.toList());
        } else {
            models = modelLibraryRepository.findByCreatedBy(username);
        }
        return ResponseEntity.ok(models);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> addModel(@RequestBody Map<String, Object> request,
                                                         HttpServletRequest httpRequest) {
        String username = (String) httpRequest.getAttribute("username");
        Map<String, Object> response = new HashMap<>();

        String recordName = (String) request.get("recordName");
        String modelType = (String) request.getOrDefault("modelType", "best");

        TrainingRecord record = trainingRecordRepository.findByRecordName(recordName).orElse(null);
        if (record == null) {
            response.put("message", "训练记录不存在");
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }

        String projectRoot = yoloService.getProjectRoot();
        String trainDirName = recordName + "_train";
        String weightsDir = projectRoot + "runs/detect/" + trainDirName + "/weights";
        String modelFile = modelType.equals("last") ? "last.pt" : "best.pt";
        String sourcePath = weightsDir + "/" + modelFile;

        if (!new File(sourcePath).exists()) {
            response.put("message", "模型文件不存在: " + sourcePath);
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }

        String savedModelsDir = projectRoot + "saved_models";
        new File(savedModelsDir).mkdirs();
        String modelName = recordName + "_" + modelType;
        String destPath = savedModelsDir + "/" + modelName + ".pt";

        try {
            Files.copy(Paths.get(sourcePath), Paths.get(destPath));
        } catch (Exception e) {
            response.put("message", "模型保存失败: " + e.getMessage());
            response.put("status", "error");
            return ResponseEntity.status(500).body(response);
        }

        Double map50 = null, map5095 = null, precision = null, recall = null;
        String resultsCsvFile = projectRoot + "runs/detect/" + trainDirName + "/results.csv";
        if (!new File(resultsCsvFile).exists()) {
            resultsCsvFile = projectRoot + "runs/detect/" + trainDirName + "/results.csv";
        }
        if (new File(resultsCsvFile).exists()) {
            try {
                List<String> lines = Files.readAllLines(Paths.get(resultsCsvFile));
                if (!lines.isEmpty()) {
                    String headerLine = lines.get(0).trim();
                    String lastLine = lines.get(lines.size() - 1).trim();
                    while (lastLine.isEmpty() && lines.size() > 1) {
                        lines.remove(lines.size() - 1);
                        lastLine = lines.get(lines.size() - 1).trim();
                    }
                    String[] headers = headerLine.split(",");
                    String[] values = lastLine.split(",");
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        String h = headers[i].trim().replaceAll("^\\s+", "").replaceAll("\\s+$", "");
                        String v = values[i].trim().replaceAll("^\\s+", "").replaceAll("\\s+$", "");
                        if (h.contains("mAP50-95") || h.contains("mAP50-95(B)")) {
                            try { map5095 = Double.parseDouble(v); } catch (Exception ignored) {}
                        } else if (h.contains("mAP50") || h.contains("mAP50(B)")) {
                            try { map50 = Double.parseDouble(v); } catch (Exception ignored) {}
                        } else if (h.contains("precision") || h.contains("precision(B)")) {
                            try { precision = Double.parseDouble(v); } catch (Exception ignored) {}
                        } else if (h.contains("recall") || h.contains("recall(B)")) {
                            try { recall = Double.parseDouble(v); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse results.csv for {}: {}", recordName, e.getMessage());
            }
        }

        ModelLibrary model = new ModelLibrary();
        model.setModelName(modelName);
        model.setDataName(record.getDataName());
        model.setRecordName(recordName);
        model.setModelType(modelType);
        model.setModelPath(destPath);
        model.setEpochs(record.getEpochs());
        model.setImgsz(record.getImgsz());
        model.setMap50(map50);
        model.setMap5095(map5095);
        model.setPrecision(precision);
        model.setRecall(recall);
        model.setCreatedBy(username);
        modelLibraryRepository.save(model);

        authService.logOperation(null, username, "SAVE_MODEL", recordName,
                "保存模型到模型库: " + modelName);

        response.put("message", "模型已保存到模型库");
        response.put("status", "success");
        response.put("modelId", model.getId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable Long id,
                                                            HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        Map<String, Object> response = new HashMap<>();

        ModelLibrary model = modelLibraryRepository.findById(id).orElse(null);
        if (model == null) {
            response.put("message", "模型不存在");
            response.put("status", "error");
            return ResponseEntity.status(404).body(response);
        }

        if (!"ROOT".equals(role) && !username.equals(model.getCreatedBy())) {
            response.put("message", "无权限删除此模型");
            response.put("status", "error");
            return ResponseEntity.status(403).body(response);
        }

        try {
            String modelPath = model.getModelPath();
            log.info("Deleting model file: {}", modelPath);
            if (modelPath != null && !modelPath.isEmpty()) {
                File file = new File(modelPath);
                if (file.exists()) {
                    if (file.delete()) {
                        log.info("Model file deleted: {}", modelPath);
                    } else {
                        log.warn("Failed to delete model file (may be in use): {}", modelPath);
                    }
                } else {
                    log.warn("Model file does not exist, skipping deletion: {}", modelPath);
                }
            }
        } catch (Exception e) {
            log.warn("Exception while deleting model file: {}", e.getMessage());
        }

        try {
            inferenceRecordRepository.findByModelId(id).forEach(ir -> {
                try { inferenceRecordRepository.delete(ir); } catch (Exception e) {
                    log.warn("Failed to delete inference record {}: {}", ir.getId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Exception while cleaning up inference records: {}", e.getMessage());
        }

        try {
            modelLibraryRepository.deleteById(id);
        } catch (Exception e) {
            log.error("Failed to delete model from database: {}", e.getMessage(), e);
            response.put("message", "删除失败: " + e.getMessage());
            response.put("status", "error");
            return ResponseEntity.status(500).body(response);
        }
        authService.logOperation(null, username, "DELETE_MODEL", model.getModelName(),
                "从模型库删除模型: " + model.getModelName());

        response.put("message", "模型已删除");
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/inferences")
    public ResponseEntity<List<InferenceRecord>> listInferences(
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String dataName,
            HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");

        List<InferenceRecord> records = inferenceRecordRepository.findAllByOrderByCreatedAtDesc();
        
        if (!"ROOT".equals(role)) {
            records = records.stream()
                    .filter(r -> username.equals(r.getCreatedBy()))
                    .collect(Collectors.toList());
        }

        if (modelName != null && !modelName.isEmpty()) {
            records = records.stream()
                    .filter(r -> r.getModelName().contains(modelName))
                    .collect(Collectors.toList());
        }

        if (dataName != null && !dataName.isEmpty()) {
            records = records.stream()
                    .filter(r -> r.getDataName().equals(dataName))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(records);
    }

    @PostMapping("/{id}/predict")
    public ResponseEntity<Map<String, Object>> predictWithModel(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        String username = (String) httpRequest.getAttribute("username");
        Map<String, Object> response = new HashMap<>();

        try {
        ModelLibrary model = modelLibraryRepository.findById(id).orElse(null);
        if (model == null) {
            response.put("message", "模型不存在");
            response.put("status", "error");
            return ResponseEntity.status(404).body(response);
        }

        String targetDataName = (String) request.get("dataName");
        if (targetDataName == null || targetDataName.isEmpty()) {
            response.put("message", "请指定目标数据集");
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            Dataset dataset = datasetRepository.findByName(targetDataName).orElse(null);
            if (dataset == null || !Boolean.TRUE.equals(dataset.getPreprocessed())) {
                response.put("message", "该数据集未预处理，无法进行推理。请先对数据集进行预处理操作。");
                response.put("status", "error");
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            log.warn("Dataset preprocessed check failed, skipping: {}", e.getMessage());
        }

        String projectRoot = yoloService.getProjectRoot();
        String predictDirName = model.getModelName() + "_predict_" + targetDataName;
        String predictDir = projectRoot + "runs/detect/" + predictDirName;

        InferenceRecord record = new InferenceRecord();
        record.setModelId(id);
        record.setModelName(model.getModelName());
        record.setDataName(targetDataName);
        record.setCreatedBy(username);
        record.setPredictDir(predictDir);
        record.setStatus("running");
        inferenceRecordRepository.save(record);
        final Long recordId = record.getId();

        authService.logOperation(null, username, "PREDICT", model.getModelName(),
                "使用模型 " + model.getModelName() + " 推理数据集 " + targetDataName);

        String jobName = K8sClientService.sanitizeK8sName(predictDirName + "-predict-job");
        String imageName = yoloService.getTrainingImage();
        String pvcName = yoloService.getPvcName();

        try {
            io.kubernetes.client.openapi.models.V1Job k8sJob = new io.kubernetes.client.openapi.models.V1Job();

            io.kubernetes.client.openapi.models.V1ObjectMeta metadata = new io.kubernetes.client.openapi.models.V1ObjectMeta();
            metadata.setName(jobName);
            java.util.Map<String, String> labels = new java.util.HashMap<>();
            labels.put("app", "yolo-training");
            labels.put("type", "predict");
            labels.put("site", targetDataName);
            metadata.setLabels(labels);
            k8sJob.setMetadata(metadata);

            io.kubernetes.client.openapi.models.V1JobSpec jobSpec = new io.kubernetes.client.openapi.models.V1JobSpec();
            jobSpec.setParallelism(1);
            jobSpec.setCompletions(1);
            jobSpec.setBackoffLimit(2);
            jobSpec.setTtlSecondsAfterFinished(3600);

            io.kubernetes.client.openapi.models.V1PodTemplateSpec template = new io.kubernetes.client.openapi.models.V1PodTemplateSpec();
            io.kubernetes.client.openapi.models.V1ObjectMeta templateMeta = new io.kubernetes.client.openapi.models.V1ObjectMeta();
            templateMeta.setLabels(labels);
            template.setMetadata(templateMeta);

            io.kubernetes.client.openapi.models.V1PodSpec podSpec = new io.kubernetes.client.openapi.models.V1PodSpec();
            podSpec.setRestartPolicy("Never");

            io.kubernetes.client.openapi.models.V1Container container = new io.kubernetes.client.openapi.models.V1Container();
            container.setName("yolo-container");
            container.setImage(imageName);
            container.setImagePullPolicy("IfNotPresent");
            container.setWorkingDir("/app/workspace");

            String modelPathRel = model.getModelPath().replace(projectRoot, "");
            if (modelPathRel.startsWith("/")) modelPathRel = modelPathRel.substring(1);

            java.util.List<String> command = java.util.Arrays.asList(
                    "python3", "/app/workspace/predict_yolo.py",
                    "--model", modelPathRel,
                    "--source", targetDataName + "_processed",
                    "--name", predictDirName,
                    "--imgsz", "640"
            );
            container.setCommand(command);

            log.info("Predict command: {}", command);

            io.kubernetes.client.openapi.models.V1EnvVar env1 = new io.kubernetes.client.openapi.models.V1EnvVar();
            env1.setName("PYTHONUNBUFFERED");
            env1.setValue("1");
            io.kubernetes.client.openapi.models.V1EnvVar env2 = new io.kubernetes.client.openapi.models.V1EnvVar();
            env2.setName("DATA_ROOT");
            env2.setValue("/app/data");
            container.setEnv(java.util.Arrays.asList(env1, env2));

            if (pvcName != null && !pvcName.isEmpty()) {
                io.kubernetes.client.openapi.models.V1VolumeMount volumeMount = new io.kubernetes.client.openapi.models.V1VolumeMount();
                volumeMount.setName("app-volume");
                volumeMount.setMountPath("/app/data");
                container.setVolumeMounts(java.util.Collections.singletonList(volumeMount));
            }

            io.kubernetes.client.openapi.models.V1ResourceRequirements resources = new io.kubernetes.client.openapi.models.V1ResourceRequirements();
            java.util.Map<String, io.kubernetes.client.custom.Quantity> resRequests = new java.util.HashMap<>();
            java.util.Map<String, io.kubernetes.client.custom.Quantity> resLimits = new java.util.HashMap<>();
            resRequests.put("cpu", new io.kubernetes.client.custom.Quantity("500m"));
            resRequests.put("memory", new io.kubernetes.client.custom.Quantity("1Gi"));
            resLimits.put("cpu", new io.kubernetes.client.custom.Quantity("2"));
            resLimits.put("memory", new io.kubernetes.client.custom.Quantity("4Gi"));
            resources.setRequests(resRequests);
            resources.setLimits(resLimits);
            container.setResources(resources);

            podSpec.setContainers(java.util.Collections.singletonList(container));

            if (pvcName != null && !pvcName.isEmpty()) {
                io.kubernetes.client.openapi.models.V1Volume volume = new io.kubernetes.client.openapi.models.V1Volume();
                volume.setName("app-volume");
                io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource pvcSource = new io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource();
                pvcSource.setClaimName(pvcName);
                volume.setPersistentVolumeClaim(pvcSource);
                podSpec.setVolumes(java.util.Collections.singletonList(volume));
            }

            template.setSpec(podSpec);
            jobSpec.setTemplate(template);
            k8sJob.setSpec(jobSpec);

            k8sClientService.createJob(k8sJob);

            new Thread(() -> {
                try {
                    for (int i = 0; i < 120; i++) {
                        Thread.sleep(5000);
                        String status = k8sClientService.getJobStatus(jobName);
                        if ("Succeeded".equals(status) || "COMPLETED".equals(status) || "DONE".equals(status)) {
                            Map<String, Double> metrics = extractMetrics(predictDir);
                            InferenceRecord ir = inferenceRecordRepository.findById(recordId).orElse(null);
                            if (ir != null) {
                                ir.setMap50(metrics.get("map50"));
                                ir.setMap5095(metrics.get("map50_95"));
                                ir.setPrecision(metrics.get("precision"));
                                ir.setRecall(metrics.get("recall"));
                                ir.setStatus("completed");
                                inferenceRecordRepository.save(ir);
                            }
                            return;
                        } else if ("Failed".equals(status) || "FAILED".equals(status)) {
                            InferenceRecord ir = inferenceRecordRepository.findById(recordId).orElse(null);
                            if (ir != null) {
                                ir.setStatus("failed");
                                inferenceRecordRepository.save(ir);
                            }
                            return;
                        }
                    }
                    InferenceRecord ir = inferenceRecordRepository.findById(recordId).orElse(null);
                    if (ir != null) {
                        ir.setStatus("failed");
                        inferenceRecordRepository.save(ir);
                    }
                } catch (Exception e) {
                    InferenceRecord ir = inferenceRecordRepository.findById(recordId).orElse(null);
                    if (ir != null) {
                        ir.setStatus("failed");
                        inferenceRecordRepository.save(ir);
                    }
                }
            }).start();

        } catch (Exception e) {
            InferenceRecord ir = inferenceRecordRepository.findById(recordId).orElse(null);
            if (ir != null) {
                ir.setStatus("failed");
                inferenceRecordRepository.save(ir);
            }
            response.put("message", "推理任务创建失败: " + e.getMessage());
            response.put("status", "error");
            return ResponseEntity.status(500).body(response);
        }

        response.put("message", "推理任务已提交");
        response.put("status", "success");
        response.put("predictDir", predictDir);
        response.put("recordId", recordId);
        return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Predict failed for model {}: {}", id, e.getMessage(), e);
            response.put("message", "推理失败: " + e.getMessage());
            response.put("status", "error");
            return ResponseEntity.status(500).body(response);
        }
    }

    private Map<String, Double> extractMetrics(String predictDir) {
        Map<String, Double> metrics = new HashMap<>();
        File resultsCsv = new File(predictDir, "results.csv");
        if (resultsCsv.exists()) {
            try {
                List<String> lines = Files.readAllLines(resultsCsv.toPath());
                if (!lines.isEmpty()) {
                    String headerLine = lines.get(0).trim();
                    String lastLine = lines.get(lines.size() - 1).trim();
                    while (lastLine.isEmpty() && lines.size() > 1) {
                        lines.remove(lines.size() - 1);
                        lastLine = lines.get(lines.size() - 1).trim();
                    }
                    String[] headers = headerLine.split(",");
                    String[] values = lastLine.split(",");
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        String h = headers[i].trim();
                        String v = values[i].trim();
                        if (h.contains("mAP50-95") || h.contains("mAP50-95(B)")) {
                            try { metrics.put("map50_95", Double.parseDouble(v)); } catch (Exception ignored) {}
                        } else if (h.contains("mAP50") || h.contains("mAP50(B)")) {
                            try { metrics.put("map50", Double.parseDouble(v)); } catch (Exception ignored) {}
                        } else if (h.contains("precision") || h.contains("precision(B)")) {
                            try { metrics.put("precision", Double.parseDouble(v)); } catch (Exception ignored) {}
                        } else if (h.contains("recall") || h.contains("recall(B)")) {
                            try { metrics.put("recall", Double.parseDouble(v)); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return metrics;
    }

    @GetMapping("/{id}/predict-results")
    public ResponseEntity<Map<String, Object>> getPredictResults(
            @PathVariable Long id,
            @RequestParam String dataName,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        ModelLibrary model = modelLibraryRepository.findById(id).orElse(null);
        if (model == null) {
            response.put("error", "模型不存在");
            return ResponseEntity.status(404).body(response);
        }

        String projectRoot = yoloService.getProjectRoot();
        String predictDirName = model.getModelName() + "_predict_" + dataName;
        String predictDir = projectRoot + "runs/detect/" + predictDirName;

        File dir = new File(predictDir);
        if (!dir.exists() || !dir.isDirectory()) {
            response.put("exists", false);
            response.put("message", "推理结果不存在");
            return ResponseEntity.ok(response);
        }

        response.put("exists", true);
        List<Map<String, Object>> images = new ArrayList<>();
        collectImageFiles(dir, "", images);
        response.put("images", images);
        response.put("count", images.size());
        return ResponseEntity.ok(response);
    }

    private void collectImageFiles(File dir, String prefix, List<Map<String, Object>> images) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String path = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                collectImageFiles(f, path, images);
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", f.getName());
                    info.put("path", path);
                    info.put("size", f.length());
                    images.add(info);
                }
            }
        }
    }

    @GetMapping("/{id}/predict-image")
    public void getPredictImage(
            @PathVariable Long id,
            @RequestParam String dataName,
            @RequestParam String imageName,
            @RequestParam(required = false) String path,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        ModelLibrary model = modelLibraryRepository.findById(id).orElse(null);
        if (model == null) {
            httpResponse.setStatus(404);
            return;
        }

        String projectRoot = yoloService.getProjectRoot();
        String predictDirName = model.getModelName() + "_predict_" + dataName;
        String baseDir = projectRoot + "runs/detect/" + predictDirName;
        String imagePath = (path != null && !path.isEmpty()) ? baseDir + "/" + path : baseDir + "/" + imageName;

        try {
            File file = new File(imagePath).getCanonicalFile();
            File baseDirFile = new File(baseDir).getCanonicalFile();
            if (!file.getPath().startsWith(baseDirFile.getPath()) || !file.exists()) {
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

    @DeleteMapping("/inferences/{id}")
    public ResponseEntity<Map<String, Object>> deleteInference(@PathVariable Long id,
                                                               HttpServletRequest httpRequest) {
        String role = (String) httpRequest.getAttribute("role");
        String username = (String) httpRequest.getAttribute("username");
        Map<String, Object> response = new HashMap<>();

        InferenceRecord record = inferenceRecordRepository.findById(id).orElse(null);
        if (record == null) {
            response.put("message", "推理记录不存在");
            response.put("status", "error");
            return ResponseEntity.status(404).body(response);
        }

        if (!"ROOT".equals(role) && !username.equals(record.getCreatedBy())) {
            response.put("message", "无权限删除此记录");
            response.put("status", "error");
            return ResponseEntity.status(403).body(response);
        }

        try {
            File dir = new File(record.getPredictDir());
            if (dir.exists() && dir.isDirectory()) {
                deleteDirectory(dir);
            }
        } catch (Exception ignored) {}

        inferenceRecordRepository.deleteById(id);

        response.put("message", "推理记录已删除");
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}