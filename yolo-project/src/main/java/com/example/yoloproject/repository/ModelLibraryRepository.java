package com.example.yoloproject.repository;

import com.example.yoloproject.entity.ModelLibrary;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ModelLibraryRepository extends JpaRepository<ModelLibrary, Long> {
    List<ModelLibrary> findByDataName(String dataName);
    List<ModelLibrary> findByRecordName(String recordName);
    List<ModelLibrary> findByCreatedBy(String createdBy);
    List<ModelLibrary> findAllByOrderByCreatedAtDesc();
}
