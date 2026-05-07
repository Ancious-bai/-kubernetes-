package com.example.yoloproject.repository;

import com.example.yoloproject.entity.TrainingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TrainingRecordRepository extends JpaRepository<TrainingRecord, Long> {
    List<TrainingRecord> findByDataName(String dataName);
    Optional<TrainingRecord> findByRecordName(String recordName);
    List<TrainingRecord> findByCreatedBy(String createdBy);
    boolean existsByRecordName(String recordName);
    void deleteByRecordName(String recordName);
    void deleteByDataName(String dataName);
}
