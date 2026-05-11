package com.example.yoloproject.controller;

import com.example.yoloproject.entity.InferenceRecord;
import com.example.yoloproject.entity.ModelLibrary;
import com.example.yoloproject.entity.TrainingRecord;
import com.example.yoloproject.repository.InferenceRecordRepository;
import com.example.yoloproject.repository.ModelLibraryRepository;
import com.example.yoloproject.repository.TrainingRecordRepository;
import com.example.yoloproject.service.AuthService;
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

    @Autowired
    private ModelLibraryRepository modelLibraryRepository;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private InferenceRecordRepository inferenceRecordRepository;

    @Autowired
    private YoloService yoloService;

    @Autowired
    private AuthService authService;

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
        String weightsDir = projectRoot + "runs/train/" + trainDirName + "/weights";
        String modelFile = modelType.equals("last") ? "last.pt" : "best.pt";
        String sourcePath = weightsDir + "/" + modelFile;

        if (!new File(sourcePath).exists()) {
            response.put("message", "模型文件不存在: " + modelFile);
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
        String resultsFile = projectRoot + "runs/train/" + trainDirName + "/results.txt";
        if (new File(resultsFile).exists()) {
            try {
                List<String> lines = Files.readAllLines(Paths.get(resultsFile));
                for (String line : lines) {
                    if (line.contains("mAP50")) {
                        String[] parts = line.split("\\s+");
                        for (int i = 0; i < parts.length; i++) {
                            if ("mAP50".equals(parts[i]) && i + 1 < parts.length) {
                                try { map50 = Double.parseDouble(parts[i + 1]); } catch (Exception ignored) {}
                            } else if ("mAP50-95".equals(parts[i]) && i + 1 < parts.length) {
                                try { map5095 = Double.parseDouble(parts[i + 1]); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
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
            File file = new File(model.getModelPath());
            if (file.exists()) file.delete();
        } catch (Exception e) {
        }

        inferenceRecordRepository.findByModelId(id).forEach(inferenceRecordRepository::delete);

        modelLibraryRepository.deleteById(id);
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

        String projectRoot = yoloService.getProjectRoot();
        String modelPath = model.getModelPath();
        String imagesDir = projectRoot + targetDataName + "/images";
        if (!new File(imagesDir).exists()) {
            imagesDir = projectRoot + targetDataName;
        }
        final String effectiveImagesDir = imagesDir;

        String predictDirName = model.getModelName() + "_predict_" + targetDataName;
        String predictDir = projectRoot + "runs/detect/" + predictDirName;
        final String effectivePredictDir = predictDir;
        final String effectivePredictName = predictDirName;

        InferenceRecord record = new InferenceRecord();
        record.setModelId(id);
        record.setModelName(model.getModelName());
        record.setDataName(targetDataName);
        record.setCreatedBy(username);
        record.setPredictDir(predictDir);
        inferenceRecordRepository.save(record);
        final Long recordId = record.getId();

        authService.logOperation(null, username, "PREDICT", model.getModelName(),
                "使用模型 " + model.getModelName() + " 推理数据集 " + targetDataName);

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "python3", "-c",
                        "from ultralytics import YOLO; model = YOLO('" + modelPath + "'); results = model.predict(source='" + effectiveImagesDir + "', save=True, project='" + projectRoot + "runs/detect', name='" + effectivePredictName + "', exist_ok=True)"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
                process.destroyForcibly();

                Map<String, Double> metrics = extractMetrics(effectivePredictDir);
                InferenceRecord ir = inferenceRecordRepository.findById(recordId).orElse(null);
                if (ir != null) {
                    ir.setMap50(metrics.get("map50"));
                    ir.setMap5095(metrics.get("map50_95"));
                    ir.setPrecision(metrics.get("precision"));
                    ir.setRecall(metrics.get("recall"));
                    ir.setStatus("completed");
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

        response.put("message", "推理任务已提交");
        response.put("status", "success");
        response.put("predictDir", predictDir);
        response.put("recordId", recordId);
        return ResponseEntity.ok(response);
    }

    private Map<String, Double> extractMetrics(String predictDir) {
        Map<String, Double> metrics = new HashMap<>();
        File resultsFile = new File(predictDir, "results.txt");
        if (resultsFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(resultsFile.toPath());
                for (String line : lines) {
                    String[] parts = line.split("\\s+");
                    for (int i = 0; i < parts.length; i++) {
                        if ("mAP50".equals(parts[i]) && i + 1 < parts.length) {
                            try { metrics.put("map50", Double.parseDouble(parts[i + 1])); } catch (Exception ignored) {}
                        } else if ("mAP50-95".equals(parts[i]) && i + 1 < parts.length) {
                            try { metrics.put("map50_95", Double.parseDouble(parts[i + 1])); } catch (Exception ignored) {}
                        } else if ("precision".equals(parts[i]) && i + 1 < parts.length) {
                            try { metrics.put("precision", Double.parseDouble(parts[i + 1])); } catch (Exception ignored) {}
                        } else if ("recall".equals(parts[i]) && i + 1 < parts.length) {
                            try { metrics.put("recall", Double.parseDouble(parts[i + 1])); } catch (Exception ignored) {}
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
        File[] files = dir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png") || name.toLowerCase().endsWith(".jpeg"));
        if (files != null) {
            for (File f : files) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", f.getName());
                info.put("size", f.length());
                images.add(info);
            }
        }
        response.put("images", images);
        response.put("count", images.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/predict-image")
    public void getPredictImage(
            @PathVariable Long id,
            @RequestParam String dataName,
            @RequestParam String imageName,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        ModelLibrary model = modelLibraryRepository.findById(id).orElse(null);
        if (model == null) {
            httpResponse.setStatus(404);
            return;
        }

        String projectRoot = yoloService.getProjectRoot();
        String predictDirName = model.getModelName() + "_predict_" + dataName;
        String imagePath = projectRoot + "runs/detect/" + predictDirName + "/" + imageName;

        try {
            File file = new File(imagePath).getCanonicalFile();
            File baseDir = new File(projectRoot + "runs/detect/" + predictDirName).getCanonicalFile();
            if (!file.getPath().startsWith(baseDir.getPath()) || !file.exists()) {
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