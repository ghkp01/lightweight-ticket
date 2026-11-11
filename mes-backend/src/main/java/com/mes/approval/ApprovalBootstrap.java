package com.mes.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * 启动时把「有节点配置但还没部署 BPMN」的审批流编译并部署一次。
 * 这样从旧引擎升级上来不用手工重新保存流程。
 */
@Component
public class ApprovalBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovalBootstrap.class);

    private final ApprovalService approvalService;

    public ApprovalBootstrap(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = approvalService.deployMissing();
            if (count > 0) {
                log.info("已为 {} 条审批流补部署 BPMN", count);
            }
        } catch (Exception error) {
            log.warn("审批流自动部署失败：{}", error.getMessage());
        }
    }
}
