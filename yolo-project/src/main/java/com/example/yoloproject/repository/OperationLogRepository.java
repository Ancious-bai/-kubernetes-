package com.example.yoloproject.repository;

import com.example.yoloproject.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    List<OperationLog> findAllByOrderByCreatedAtDesc();
    List<OperationLog> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<OperationLog> findByUsernameOrderByCreatedAtDesc(String username);
}
