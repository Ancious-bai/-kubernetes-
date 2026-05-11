package com.example.yoloproject.repository;

import com.example.yoloproject.entity.NodeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NodeLogRepository extends JpaRepository<NodeLog, Long> {
    List<NodeLog> findByNodeNameOrderByStartedAtDesc(String nodeName);
    List<NodeLog> findAllByOrderByStartedAtDesc();
    List<NodeLog> findByRecordName(String recordName);
}
