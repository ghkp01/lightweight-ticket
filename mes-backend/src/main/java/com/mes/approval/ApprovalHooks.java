package com.mes.approval;

import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BPMN 里 serviceTask 调用的钩子：抄送与自动通过。
 * 这两个动作不产生待办，但要在审批时间线里留痕，所以写一条 sys_approval_log。
 */
@Component("approvalHooks")
public class ApprovalHooks {

    private final NoticePort notice;
    private final JdbcTemplate jdbcTemplate;
    private final ApprovalWebhook webhook;

    public ApprovalHooks(NoticePort notice, JdbcTemplate jdbcTemplate,
                         ApprovalWebhook webhook) {
        this.notice = notice;
        this.jdbcTemplate = jdbcTemplate;
        this.webhook = webhook;
    }

    /** 抄送：走到这个节点，就给节点策略里配的抄送人推通知 */
    public void notifyCc(DelegateExecution execution) {
        String nodeKey = execution.getCurrentActivityId();
        List<String> receivers = ccUsersOf(execution, nodeKey);
        String applicant = String.valueOf(execution.getVariable("applicant"));
        String title = String.valueOf(execution.getVariable("title"));
        String bizNo = String.valueOf(execution.getVariable("bizNo"));
        String nodeName = nodeName(execution, nodeKey);
        int sent = 0;
        for (String username : receivers) {
            if (username.equals(applicant)) {
                continue;
            }
            notice.push(username, NoticeMessage.of("INFO", "抄送：" + title,
                    "单据 " + bizNo + " 已到「" + nodeName + "」，申请人 " + applicant + "。",
                    "APPROVAL", execution.getProcessInstanceId()));
            sent++;
        }
        log(execution, nodeKey, nodeName, "CC", "已抄送 " + sent + " 人");
    }

    /** 抄送人来自节点策略表（flow_id + node_key），不再依赖流程变量 */
    private List<String> ccUsersOf(DelegateExecution execution, String nodeKey) {
        Object flowId = execution.getVariable("flowId");
        if (flowId == null) {
            return List.of();
        }
        try {
            List<String> rows = jdbcTemplate.queryForList("""
                    select cc_users from sys_approval_node_policy where flow_id = ? and node_key = ?
                    """, String.class, ((Number) flowId).longValue(), nodeKey);
            return rows.isEmpty() ? List.of() : split(rows.get(0));
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /**
     * 按角色取人：给「按角色会签」的多实例集合用。
     * 画布编译时只知道角色码，具体是谁要到发起那一刻才查库。
     */
    public List<String> roleUsers(String roles) {
        List<String> codes = split(roles).stream().map(item -> item.toUpperCase(Locale.ROOT)).toList();
        if (codes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", codes.stream().map(item -> "?").toList());
        try {
            return jdbcTemplate.queryForList("""
                    select username from sys_user
                    where enabled = true and role in (%s) order by id
                    """.formatted(placeholders), String.class, codes.toArray());
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /**
     * 这个节点有没有审批人：没有就让排他网关把整个节点跳过（等价「审批人为空自动通过」）。
     * ALWAYS 用于发起人 / 主管这类一定解析得出人的节点。
     */
    public boolean hasApprover(String type, String values) {
        String kind = type == null ? "ROLE" : type.toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "ALWAYS" -> true;
            case "USER" -> !split(values).isEmpty();
            default -> !roleUsers(values).isEmpty();
        };
    }

    /**
     * 把逗号串转成 List：会签的 collection 必须是集合，
     * 直接把 "u1,u2" 写进 flowable:collection 会被引擎当成变量名去找。
     */
    public List<String> asList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> result = split(csv);
        return result.isEmpty() ? List.of(csv) : result;
    }

    /** 或签候选人：给「空审批人转管理员」策略用，解析不到人时兜底给 admin */
    public String approverUsers(String type, String values) {
        List<String> users = resolveApprovers(type, values);
        return users.isEmpty() ? "admin" : String.join(",", users);
    }

    /** 会签集合：同上，解析不到人就只让 admin 一个人签 */
    public List<String> approverList(String type, String values) {
        List<String> users = resolveApprovers(type, values);
        return users.isEmpty() ? List.of("admin") : users;
    }

    private List<String> resolveApprovers(String type, String values) {
        String kind = type == null ? "ROLE" : type.toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "USER" -> split(values);
            case "INITIATOR" -> List.of();
            default -> roleUsers(values);
        };
    }

    /** 自动通过：不需要人处理，仅留痕 */
    public void autoPass(DelegateExecution execution) {
        String nodeKey = execution.getCurrentActivityId();
        log(execution, nodeKey, nodeName(execution, nodeKey), "AUTO", "系统自动通过");
    }

    /**
     * 自定义逻辑：节点上配了 url 字段就 POST 一份 JSON 出去（流程变量 + 节点信息）。
     * 失败只记日志，不阻断审批。
     */
    public void custom(DelegateExecution execution) {
        String nodeKey = execution.getCurrentActivityId();
        String name = nodeName(execution, nodeKey);
        String url = null;
        try {
            if (execution.getCurrentFlowElement()
                    instanceof org.flowable.bpmn.model.ServiceTask serviceTask
                    && serviceTask.getExtensionElements() != null) {
                for (org.flowable.bpmn.model.ExtensionElement field
                        : serviceTask.getExtensionElements().getOrDefault("field", java.util.List.of())) {
                    if ("url".equals(field.getAttributeValue(null, "name"))) {
                        url = field.getAttributeValue(null, "stringValue");
                    }
                }
            }
        } catch (RuntimeException error) {
            log(execution, nodeKey, name, "CUSTOM", "读取节点配置失败：" + error.getMessage());
            return;
        }
        if (url == null || url.isBlank()) {
            log(execution, nodeKey, name, "CUSTOM", "节点没有配置回调地址，跳过");
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("instanceId", execution.getProcessInstanceId());
        payload.put("node", name);
        payload.put("bizType", execution.getVariable("bizType"));
        payload.put("bizId", execution.getVariable("bizId"));
        payload.put("bizNo", execution.getVariable("bizNo"));
        payload.put("title", execution.getVariable("title"));
        payload.put("applicant", execution.getVariable("applicant"));
        payload.put("qty", execution.getVariable("qty"));
        payload.put("amount", execution.getVariable("amount"));
        payload.put("at", java.time.OffsetDateTime.now().toString());
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception error) {
            json = "{}";
        }
        boolean ok = webhook.post(url, payload, json);
        log(execution, nodeKey, name, "CUSTOM", (ok ? "回调成功：" : "回调失败：") + url);
    }

    private String nodeName(DelegateExecution execution, String nodeKey) {
        try {
            Object name = execution.getCurrentFlowElement() == null
                    ? null : execution.getCurrentFlowElement().getName();
            return name == null ? nodeKey : String.valueOf(name);
        } catch (RuntimeException error) {
            return nodeKey;
        }
    }

    private void log(DelegateExecution execution, String nodeKey, String nodeName,
                     String action, String comment) {
        jdbcTemplate.update("""
                insert into sys_approval_log (instance_id, node_key, node_name, action, comment, operator)
                values (?, ?, ?, ?, ?, 'system')
                """, execution.getProcessInstanceId(), nodeKey, nodeName, action, comment);
    }

    private static List<String> split(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && !"null".equals(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
