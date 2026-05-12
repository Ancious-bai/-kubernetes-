package com.example.yoloproject.repository;

import com.example.yoloproject.entity.InferenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InferenceRecordRepository extends JpaRepository<InferenceRecord, Long> {
    List<InferenceRecord> findByModelId(Long modelId);
    Optional<InferenceRecord> findByModelIdAndDataName(Long modelId, String dataName);
    List<InferenceRecord> findByDataName(String dataName);
    List<InferenceRecord> findByCreatedBy(String createdBy);
    List<InferenceRecord> findAllByOrderByCreatedAtDesc();
    List<InferenceRecord> findByModelNameContaining(String modelName);
}
