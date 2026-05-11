package com.example.yoloproject.controller;

import com.example.yoloproject.entity.ModelLibrary;
import com.example.yoloproject.entity.TrainingRecord;
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
        String weightsDir = projectRoot + "runs/detect/" + trainDirName + "/weights";
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

        ModelLibrary model = new ModelLibrary();
        model.setModelName(modelName);
        model.setDataName(record.getDataName());
        model.setRecordName(recordName);
        model.setModelType(modelType);
        model.setModelPath(destPath);
        model.setEpochs(record.getEpochs());
        model.setImgsz(record.getImgsz());
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

        modelLibraryRepository.deleteById(id);
        authService.logOperation(null, username, "DELETE_MODEL", model.getModelName(),
                "从模型库删除模型: " + model.getModelName());

        response.put("message", "模型已删除");
        response.put("status", "success");
        return ResponseEntity.ok(response);
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

        String predictDir = projectRoot + "runs/detect/" + model.getModelName() + "_predict_" + targetDataName;
        String command = String.format(
                "python3 -c \"from ultralytics import YOLO; model = YOLO('%s'); results = model.predict(source='%s', save=True, project='%s/runs/detect', name='%s', exist_ok=True)\"",
                modelPath, imagesDir, projectRoot, model.getModelName() + "_predict_" + targetDataName);

        authService.logOperation(null, username, "PREDICT", model.getModelName(),
                "使用模型 " + model.getModelName() + " 推理数据集 " + targetDataName);

        response.put("message", "推理任务已提交");
        response.put("status", "success");
        response.put("predictDir", predictDir);
        return ResponseEntity.ok(response);
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
}
