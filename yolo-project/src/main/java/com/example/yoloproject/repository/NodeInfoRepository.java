package com.example.yoloproject.repository;

import com.example.yoloproject.entity.NodeInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NodeInfoRepository extends JpaRepository<NodeInfo, Long> {

    Optional<NodeInfo> findByNodeName(String nodeName);

    boolean existsByNodeName(String nodeName);

    List<NodeInfo> findByReadyTrue();

    List<NodeInfo> findBySchedulableTrue();

    void deleteByNodeName(String nodeName);
}
