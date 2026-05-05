package com.example.yoloproject.controller;

import com.example.yoloproject.dto.JobStatus;
import com.example.yoloproject.service.YoloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebSocketController {

    @Autowired
    private YoloService yoloService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedRate = 2000)
    public void sendStatusUpdates() {
        for (Map.Entry<String, JobStatus> entry : yoloService.getJobStatusMap().entrySet()) {
            messagingTemplate.convertAndSend("/topic/status/" + entry.getKey(), entry.getValue());
        }
    }
}
