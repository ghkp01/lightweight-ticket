package com.mes.approval;

import com.mes.approval.api.ApprovalPort;
import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.cost.api.PerformanceApprovalPort;
import com.mes.masterdata.api.BomApprovalPort;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.scope.OrgScopeService;
import com.mes.purchase.api.RequisitionApprovalPort;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.TransferOrderPort;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审批流：配置（画布节点树）+ 执行（Flowable BPMN 2.0）。
 * <p>
 * 配置侧仍是我们自己的三张表，画布保存时把节点树编译成 BPMN 并部署到 Flowable；
 * 执行侧（运行中的实例与待办）全部交给 Flowable，所以并行网关、排他网关、多实例会签、
 * 流程版本并存这些能力都是引擎原生的，不再自己实现。
 * <p>
 * 节点树：每个节点带 itemKey，子节点用 parentKey + branchKey 指向所属容器（条件分支的 TRUE/FALSE、
 * 并行分支的 B0/B1...）。审批人为空、同人去重、超时这些「引擎不关心」的策略在提交时与定时扫描里处理。
 */
@Service
public class ApprovalService implements ApprovalPort {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** 可选的审批角色，与 sys_user.role 一致 */
    public static final List<String> ROLES = List.of("ADMIN", "SALES", "PLANNER", "WAREHOUSE", "QC",
            "OPERATOR");
    /** 条件字段白名单：前四个是历史字段，后五个是「按采购种类分流」真正要用的 */
    public static final List<String> CONDITION_FIELDS = List.of("AMOUNT", "QTY", "APPLICANT_ROLE", "BIZ_TYPE",
            "ITEM_COUNT", "CATEGORY", "DEPARTMENT", "SOURCE_TYPE", "URGENT");
    /** 流程匹配规则可用的运算符，in / not in 用逗号分隔多个值 */
    public static final List<String> MATCH_OPS = List.of(">=", "<=", ">", "<", "=", "!=", "IN", "NOT IN");
    private static final java.util.Set<String> NUMERIC_FIELDS =
            java.util.Set.of("AMOUNT", "QTY", "ITEM_COUNT", "BIZ_ID");
    private static final List<String> CONDITION_OPS = List.of(">=", "<=", ">", "<", "=", "!=");
    /** 节点类型 */
    public static final List<String> NODE_KINDS = List.of("APPROVER", "CC", "AUTO", "CONDITION", "PARALLEL");
    /** 审批人来源 */
    public static final List<String> APPROVER_TYPES = List.of("ROLE", "USER", "INITIATOR", "ORG_LEADER");
    private static final List<String> EMPTY_STRATEGIES = List.of("AUTO_PASS", "ADMIN");
    private static final List<String> TIMEOUT_ACTIONS = List.of("REMIND", "AUTO_PASS");

    private final JdbcTemplate jdbcTemplate;
    private final NoticePort notice;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    private final ApprovalWebhook approvalWebhook;
    private final OrgScopeService orgScope;
    private final TransferOrderPort transferOrders;
    private final PerformanceApprovalPort performanceApproval;
    private final RequisitionApprovalPort requisitionApproval;
    private final BomApprovalPort bomApproval;

    public ApprovalService(JdbcTemplate jdbcTemplate, NoticePort notice,
                          RepositoryService repositoryService, RuntimeService runtimeService,
                          TaskService taskService, HistoryService historyService,
                          IdentityService identityService, ApprovalWebhook approvalWebhook,
                          OrgScopeService orgScope,
                          TransferOrderPort transferOrders,
                          PerformanceApprovalPort performanceApproval,
                          RequisitionApprovalPort requisitionApproval,
                          BomApprovalPort bomApproval) {
        this.jdbcTemplate = jdbcTemplate;
        this.notice = notice;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.identityService = identityService;
        this.approvalWebhook = approvalWebhook;
        this.orgScope = orgScope;
        this.transferOrders = transferOrders;
        this.performanceApproval = performanceApproval;
        this.requisitionApproval = requisitionApproval;
        this.bomApproval = bomApproval;
    }

    /**
     * 灰度规则。
     *
     * <p>version 为空表示没开灰度；否则先看定向名单（命中就 100% 走灰度版），
     * 名单之外的人再按 percent 随机分流，percent=0 表示只放给定向名单。</p>
     */
    private record CanaryRule(Integer version, int percent, Set<Long> factories, Set<String> users) {

        static CanaryRule none() {
            return new CanaryRule(null, 0, Set.of(), Set.of());
        }

        boolean active() {
            return version != null;
        }

        /** 定向命中：按人或按工厂，命中任意一个就算 */
        boolean matches(String applicant, Long factoryOrgId) {
            if (applicant != null && users.stream().anyMatch(user -> user.equalsIgnoreCase(applicant))) {
                return true;
            }
            return factoryOrgId != null && factories.contains(factoryOrgId);
        }
    }

    /** 灰度配置入参：版本 + 比例 + 定向工厂 + 定向用户 */
    public record CanaryRequest(Integer version, Integer percent, List<Long> factoryOrgIds,
                                List<String> usernames, String note) {

        public CanaryRequest(Integer version, Integer percent) {
            this(version, percent, null, null, null);
        }
    }

    public record NodeRequest(Integer seq, String name, String approverType, List<String> approverValues,
                              String multiMode, String conditionField, String conditionOp,
                              String conditionValue, List<String> ccUsers, String remark,
                              String nodeKind, String parentKey, String branchKey, Integer levelUp,
                              String emptyStrategy, Boolean dedupeSameUser, Boolean allowTransfer,
                              Boolean allowAddSign, Integer timeoutHours, String timeoutAction,
                              String itemKey) {

        /** 老调用方（扁平节点）用这个，key 由服务端按顺序生成 */
        public NodeRequest(Integer seq, String name, String approverType, List<String> approverValues,
                           String multiMode, String conditionField, String conditionOp,
                           String conditionValue, List<String> ccUsers, String remark,
                           String nodeKind, String groupKey, Integer levelUp, String emptyStrategy,
                           Boolean dedupeSameUser, Boolean allowTransfer, Boolean allowAddSign,
                           Integer timeoutHours, String timeoutAction) {
            this(seq, name, approverType, approverValues, multiMode, conditionField, conditionOp,
                    conditionValue, ccUsers, remark, nodeKind, groupKey, null, levelUp, emptyStrategy,
                    dedupeSameUser, allowTransfer, allowAddSign, timeoutHours, timeoutAction, null);
        }

        public NodeRequest(Integer seq, String name, String approverType, List<String> approverValues,
                           String multiMode, String conditionField, String conditionOp,
                           String conditionValue, List<String> ccUsers, String remark) {
            this(seq, name, approverType, approverValues, multiMode, conditionField, conditionOp,
                    conditionValue, ccUsers, remark, "APPROVER", null, null, 1, "AUTO_PASS",
                    true, true, true, null, null, null);
        }
    }

    public record FlowRequest(String code, String name, String bizType, Boolean enabled,
                              String remark, List<NodeRequest> nodes, String bpmnXml,
                              String webhookUrl, List<MatchRule> matchRules, Integer priority,
                              String matchNote) {

        public FlowRequest(String code, String name, String bizType, Boolean enabled,
                           String remark, List<NodeRequest> nodes, String bpmnXml,
                           String webhookUrl) {
            this(code, name, bizType, enabled, remark, nodes, bpmnXml, webhookUrl, null, null, null);
        }

        public FlowRequest(String code, String name, String bizType, Boolean enabled,
                           String remark, List<NodeRequest> nodes, String bpmnXml) {
            this(code, name, bizType, enabled, remark, nodes, bpmnXml, null, null, null, null);
        }

        /** 老调用方（按节点树提交）用这个 */
        public FlowRequest(String code, String name, String bizType, Boolean enabled,
                           String remark, List<NodeRequest> nodes) {
            this(code, name, bizType, enabled, remark, nodes, null, null, null, null, null);
        }
    }

    // ---------------------------------------------------------------- 流程定义

    public List<Map<String, Object>> flows() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select f.id, f.code, f.name, f.biz_type as "bizType", f.enabled, f.remark,
                       f.process_key as "processKey", f.process_version as "processVersion", f.webhook_url as "webhookUrl",
                       f.stable_version as "stableVersion", f.canary_version as "canaryVersion",
                       f.canary_percent as "canaryPercent", f.signature_required as "signatureRequired",
                       f.canary_factory_ids as "canaryFactoryIds", f.canary_users as "canaryUsers",
                       f.canary_note as "canaryNote", f.priority, f.match_rules as "matchRules",
                       f.match_note as "matchNote",
                       to_char(f.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sys_approval_flow f
                order by f.biz_type, f.priority desc, f.id
                """);
        enrichFlows(rows);
        return rows;
    }

    /** 审批流列表分页；不传 page 时仍走 flows() 返回全量 */
    public PageResult flowsPage(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Long total = jdbcTemplate.queryForObject("select count(*) from sys_approval_flow", Long.class);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select f.id, f.code, f.name, f.biz_type as "bizType", f.enabled, f.remark,
                       f.process_key as "processKey", f.process_version as "processVersion", f.webhook_url as "webhookUrl",
                       f.stable_version as "stableVersion", f.canary_version as "canaryVersion",
                       f.canary_percent as "canaryPercent", f.signature_required as "signatureRequired",
                       f.canary_factory_ids as "canaryFactoryIds", f.canary_users as "canaryUsers",
                       f.canary_note as "canaryNote", f.priority, f.match_rules as "matchRules",
                       f.match_note as "matchNote",
                       to_char(f.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sys_approval_flow f
                order by f.biz_type, f.priority desc, f.id
                limit ? offset ?
                """, safeSize, (long) (safePage - 1) * safeSize);
        enrichFlows(rows);
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    private void enrichFlows(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> nodes = bpmnNodes(String.valueOf(row.get("code")));
            row.put("nodes", nodes);
            row.put("nodeCount", nodes.size());
            row.put("pendingCount", countRunning(String.valueOf(row.get("code"))));
            row.put("canaryTargetCount",
                    splitIds(row.get("canaryFactoryIds")).size() + splitNames(row.get("canaryUsers")).size());
            List<Map<String, Object>> rules = parseRules(row.get("matchRules"));
            row.put("matchRules", rules == null ? List.of() : rules);
            row.put("catchAll", rules != null && rules.isEmpty());
            row.put("matchSummary", summarizeRules(rules));
        }
    }

    /** 规则摘要：给列表页一眼看出这条链的条件 */
    public static String summarizeRules(List<Map<String, Object>> rules) {
        if (rules == null) {
            return "条件格式有误";
        }
        if (rules.isEmpty()) {
            return "不限条件（兜底）";
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            parts.add(ruleField(rule) + " " + ruleOp(rule) + " " + ruleValue(rule));
        }
        return String.join(" 且 ", parts);
    }

    public Map<String, Object> flow(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, code, name, biz_type as "bizType", enabled, remark,
                       process_key as "processKey", process_version as "processVersion",
                       bpmn_xml as "bpmnXml", priority, match_rules as "matchRules", match_note as "matchNote",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sys_approval_flow where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> rules = parseRules(detail.get("matchRules"));
        detail.put("matchRules", rules == null ? List.of() : rules);
        detail.put("matchSummary", summarizeRules(rules));
        detail.put("nodes", bpmnNodes(String.valueOf(rows.get(0).get("code"))));
        return detail;
    }

    /**
     * 节点清单：直接从已部署的 BPMN 里取用户任务。
     *
     * <p>{@code sys_approval_node} 已经整表删除——BPMN 是唯一事实来源，
     * 节点信息按需从模型里读，避免画布与节点表各说一套。</p>
     */
    private List<Map<String, Object>> bpmnNodes(String processKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (blank(processKey)) {
            return result;
        }
        try {
            ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey).latestVersion().singleResult();
            if (definition == null) {
                return result;
            }
            org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(definition.getId());
            if (model == null) {
                return result;
            }
            for (org.flowable.bpmn.model.Process process : model.getProcesses()) {
                for (org.flowable.bpmn.model.FlowElement element : process.getFlowElements()) {
                    if (!(element instanceof org.flowable.bpmn.model.UserTask task)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("itemKey", task.getId());
                    row.put("name", task.getName() == null ? task.getId() : task.getName());
                    row.put("nodeKind", "APPROVER");
                    row.put("multiMode", task.getLoopCharacteristics()
                            instanceof org.flowable.bpmn.model.MultiInstanceLoopCharacteristics ? "ALL" : "ANY");
                    result.add(row);
                }
            }
            // 抄送节点是服务任务，模型上没有便捷的表达式 getter，直接扫一遍 XML 补进来，
            // 这样「抄送给谁」也能在节点策略里配
            String xml = jdbcTemplate.queryForObject(
                    "select bpmn_xml from sys_approval_flow where code = ?", String.class, processKey);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<serviceTask\\b[^>]*>", java.util.regex.Pattern.DOTALL)
                    .matcher(xml == null ? "" : xml);
            while (matcher.find()) {
                String tag = matcher.group();
                if (!tag.contains("approvalHooks.notifyCc")) {
                    continue;
                }
                String id = attrOf(tag, "id");
                if (id == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("itemKey", id);
                row.put("name", attrOf(tag, "name") == null ? id : attrOf(tag, "name"));
                row.put("nodeKind", "CC");
                row.put("multiMode", "ANY");
                result.add(row);
            }
        } catch (RuntimeException error) {
            log.warn("读取流程节点失败：{}", error.getMessage());
        }
        return result;
    }

    private String attrOf(String tag, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(name + "=\"([^\"]*)\"").matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 新增或更新流程：节点树编译成 BPMN 部署（结构+审批人进 XML，业务策略进策略表），旧版本上的在途实例不受影响 */
    @Transactional
    public long saveFlow(Long id, FlowRequest request) {
        if (request == null || blank(request.code()) || blank(request.name()) || blank(request.bizType())) {
            throw new BizException(1002, "流程编码、名称与业务类型不能为空");
        }
        List<NodeRequest> nodes = request.nodes() == null ? List.of() : request.nodes();
        boolean xmlMode = !blank(request.bpmnXml());
        if (!xmlMode && nodes.isEmpty()) {
            throw new BizException(1002, "审批流至少要有一个节点");
        }
        boolean enabled = request.enabled() == null || request.enabled();
        String bizType = request.bizType().trim().toUpperCase(Locale.ROOT);
        List<MatchRule> matchRules = normalizeMatchRules(request.matchRules());
        int priority = request.priority() == null ? 100 : request.priority();
        if (priority < 1 || priority > 999) {
            throw new BizException(1002, "优先级请在 1 ~ 999 之间，数字越大越先匹配");
        }
        // 同业务类型可以挂多条：靠适用条件分流，但优先级不能撞，不限条件的兜底只能有一条
        if (enabled) {
            List<Map<String, Object>> others = jdbcTemplate.queryForList("""
                    select code, priority, match_rules as "matchRules"
                    from sys_approval_flow
                    where biz_type = ? and enabled = true and id <> coalesce(?, -1)
                    """, bizType, id);
            for (Map<String, Object> other : others) {
                int otherPriority = other.get("priority") == null
                        ? 100 : ((Number) other.get("priority")).intValue();
                if (otherPriority == priority) {
                    throw new BizException(4042, "业务类型 " + bizType + " 的优先级 " + priority
                            + " 已被流程「" + other.get("code") + "」占用");
                }
                List<Map<String, Object>> otherRules = parseRules(other.get("matchRules"));
                if (matchRules.isEmpty() && otherRules != null && otherRules.isEmpty()) {
                    throw new BizException(4042, "业务类型 " + bizType + " 已有一条不限条件的兜底流程（"
                            + other.get("code") + "），请给它加适用条件，或把本条改成兜底");
                }
            }
        }
        List<NodeRequest> normalized = xmlMode ? List.of() : normalizeNodes(nodes);
        if (!xmlMode) {
            normalized.forEach(this::validateNode);
        }
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        Integer sameCode = jdbcTemplate.queryForObject(
                "select count(*) from sys_approval_flow where code = ? and id <> coalesce(?, -1)",
                Integer.class, code, id);
        if (sameCode != null && sameCode > 0) {
            throw new BizException(1002, "流程编码 " + code + " 已被占用");
        }

        Long flowId = id;
        String rulesJson = matchRulesJson(matchRules);
        if (flowId == null) {
            flowId = jdbcTemplate.queryForObject("""
                    insert into sys_approval_flow (code, name, biz_type, enabled, remark, created_by,
                                                   process_key, webhook_url, match_rules, priority, match_note)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning id
                    """, Long.class, code, request.name().trim(), bizType, enabled,
                    blankToNull(request.remark()), CurrentUser.name(), code,
                    blankToNull(request.webhookUrl()), rulesJson, priority,
                    blankToNull(request.matchNote()));
        } else {
            int updated = jdbcTemplate.update("""
                    update sys_approval_flow set code = ?, name = ?, biz_type = ?, enabled = ?, remark = ?,
                           process_key = ?, webhook_url = ?, match_rules = ?::jsonb, priority = ?, match_note = ?
                    where id = ?
                    """, code, request.name().trim(), bizType, enabled, blankToNull(request.remark()),
                    code, blankToNull(request.webhookUrl()), rulesJson, priority,
                    blankToNull(request.matchNote()), flowId);
            if (updated == 0) {
                throw new BizException(4041, "审批流不存在");
            }
        }

        if (xmlMode) {
            // 建模器直接保存 BPMN：流程结构与节点策略都在 XML 里
            deployXml(flowId, code, request.bpmnXml());
            return flowId;
        }

        // 旧版「节点树」入参只作为编译输入：结构 + 审批人编译进 BPMN，
        // 超时 / 转交 / 加签 / 抄送这些策略落策略表，两边都不再有并行维护的节点表
        deploy(flowId, code, request.name().trim(), normalized);
        saveNodePolicies(flowId, normalized.stream()
                .filter(node -> "APPROVER".equals(normalizeKind(node.nodeKind()))
                        || "CC".equals(normalizeKind(node.nodeKind())))
                .map(node -> new NodePolicyRequest(node.itemKey(), node.timeoutHours(), node.timeoutAction(),
                        node.allowTransfer() == null || node.allowTransfer(),
                        node.allowAddSign() == null || node.allowAddSign(),
                        String.join(",", node.ccUsers() == null ? List.of() : cleanValues(node.ccUsers()))))
                .toList());
        return flowId;
    }

    /**
     * 直接部署建模器保存的 BPMN：把 process id 强制改成流程编码，
     * 保证 submit 里按 code 找流程定义时能对上。
     */
    public void deployXml(long flowId, String processKey, String xml) {
        String prepared = xml.replaceFirst("(?s)<process\\b[^>]*\\bid=\"[^\"]*\"",
                "<process id=\"" + processKey + "\"");
        try {
            Deployment deployment = repositoryService.createDeployment()
                    .name(processKey)
                    .addString(processKey + ".bpmn20.xml", prepared)
                    .deploy();
            ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId()).singleResult();
            // 没开灰度时，新部署的版本就是稳定版本；
            // 灰度进行中（canary_version 非空）则保持原稳定版不动，否则定向灰度会被新版本顶掉
            jdbcTemplate.update("""
                    update sys_approval_flow set bpmn_xml = ?, deployment_id = ?, process_version = ?,
                           stable_version = case when canary_version is null then ? else stable_version end
                    where id = ?
                    """, prepared, deployment.getId(),
                    definition == null ? 1 : definition.getVersion(),
                    definition == null ? 1 : definition.getVersion(), flowId);
        } catch (FlowableException error) {
            throw new BizException(4051, "BPMN 部署失败：" + error.getMessage());
        }
    }

    /** 编译节点树并部署；部署记录版本号回写到流程表 */
    public void deploy(long flowId, String processKey, String processName, List<NodeRequest> nodes) {
        List<BpmnBuilder.FlowNode> tree = new ArrayList<>();
        for (NodeRequest node : nodes) {
            tree.add(new BpmnBuilder.FlowNode(node.itemKey(), normalizeKind(node.nodeKind()),
                    node.name().trim(), blankToNull(node.parentKey()), blankToNull(node.branchKey()),
                    normalizeType(node.approverType()),
                    "ALL".equalsIgnoreCase(node.multiMode()) ? "ALL" : "ANY",
                    cleanValues(node.approverValues()),
                    node.levelUp() == null || node.levelUp() < 1 ? 1 : node.levelUp(),
                    normalizeChoice(node.emptyStrategy(), EMPTY_STRATEGIES, "AUTO_PASS"),
                    blankToNull(node.conditionField()), blankToNull(node.conditionOp()),
                    blankToNull(node.conditionValue())));
        }
        String xml = BpmnBuilder.build(processKey, processName, tree);
        deployXml(flowId, processKey, xml);
    }

    /**
     * 启动时把「还没部署」的启用流程补部署一次（BPMN 是唯一来源，没有 XML 的跳过）。
     *
     * <p>加 for update skip locked：多节点同时启动时只有一个节点会拿到这批行去部署，
     * 否则每个节点都部署一次，流程版本号会凭空跳好几个，灰度与稳定版的版本基线就乱了。</p>
     */
    @Transactional
    public int deployMissing() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, code, name, bpmn_xml as "bpmnXml"
                from sys_approval_flow
                where enabled = true and bpmn_xml is not null and deployment_id is null
                order by id
                for update skip locked
                """);
        int count = 0;
        for (Map<String, Object> row : rows) {
            long flowId = ((Number) row.get("id")).longValue();
            deployXml(flowId, String.valueOf(row.get("code")), str(row.get("bpmnXml")));
            count++;
        }
        return count;
    }

    @Transactional
    public void toggleFlow(long id, boolean enabled) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select biz_type, priority, match_rules as "matchRules"
                from sys_approval_flow where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        if (enabled) {
            // 同业务类型可以多条链并存：只拦「优先级撞车」和「第二条兜底」
            String bizType = String.valueOf(rows.get(0).get("biz_type"));
            int priority = rows.get(0).get("priority") == null
                    ? 100 : ((Number) rows.get(0).get("priority")).intValue();
            List<Map<String, Object>> rules = parseRules(rows.get(0).get("matchRules"));
            List<Map<String, Object>> others = jdbcTemplate.queryForList("""
                    select code, priority, match_rules as "matchRules"
                    from sys_approval_flow
                    where biz_type = ? and enabled = true and id <> ?
                    """, bizType, id);
            for (Map<String, Object> other : others) {
                int otherPriority = other.get("priority") == null
                        ? 100 : ((Number) other.get("priority")).intValue();
                if (otherPriority == priority) {
                    throw new BizException(4042, "优先级 " + priority + " 已被流程「"
                            + other.get("code") + "」占用，先改优先级再启用");
                }
                List<Map<String, Object>> otherRules = parseRules(other.get("matchRules"));
                if (rules != null && rules.isEmpty() && otherRules != null && otherRules.isEmpty()) {
                    throw new BizException(4042, "该业务类型已有一条不限条件的兜底流程（"
                            + other.get("code") + "），请先给它加适用条件");
                }
            }
        }
        jdbcTemplate.update("update sys_approval_flow set enabled = ? where id = ?", enabled, id);
    }

    @Transactional
    public void deleteFlow(long id) {
        List<String> codes = jdbcTemplate.queryForList(
                "select code from sys_approval_flow where id = ?", String.class, id);
        if (codes.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        if (countAllInstances(codes.get(0)) > 0) {
            throw new BizException(4043, "该流程已有审批记录，只能停用不能删除");
        }
        jdbcTemplate.update("delete from sys_approval_flow where id = ?", id);
        repositoryService.createDeploymentQuery().deploymentName(codes.get(0)).list()
                .forEach(item -> repositoryService.deleteDeployment(item.getId(), true));
    }

    /** 补齐节点树信息：没有 itemKey 的按顺序生成，没写父节点时按顺序挂到最近的容器（兼容老配置） */
    private List<NodeRequest> normalizeNodes(List<NodeRequest> nodes) {
        List<NodeRequest> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            NodeRequest node = nodes.get(index);
            String key = blank(node.itemKey()) ? "n" + (index + 1) : node.itemKey().trim();
            if (!used.add(key)) {
                throw new BizException(1002, "节点标识 " + key + " 重复");
            }
            result.add(new NodeRequest(index + 1, node.name(), node.approverType(), node.approverValues(),
                    node.multiMode(), node.conditionField(), node.conditionOp(), node.conditionValue(),
                    node.ccUsers(), node.remark(), node.nodeKind(), node.parentKey(), node.branchKey(),
                    node.levelUp(), node.emptyStrategy(), node.dedupeSameUser(), node.allowTransfer(),
                    node.allowAddSign(), node.timeoutHours(), node.timeoutAction(), key));
        }
        for (NodeRequest node : result) {
            if (!blank(node.parentKey()) && used.stream().noneMatch(key -> key.equals(node.parentKey()))) {
                throw new BizException(1002, "节点「" + node.name() + "」的父节点不存在");
            }
        }
        return result;
    }

    private void validateNode(NodeRequest node) {
        if (node == null || blank(node.name())) {
            throw new BizException(1002, "节点名称不能为空");
        }
        String name = node.name().trim();
        String kind = normalizeKind(node.nodeKind());
        String type = normalizeType(node.approverType());
        List<String> values = cleanValues(node.approverValues());
        if ("CC".equals(kind) && (node.ccUsers() == null || cleanValues(node.ccUsers()).isEmpty())) {
            throw new BizException(1002, "抄送节点「" + name + "」没有选择抄送人");
        }
        if ("APPROVER".equals(kind)) {
            boolean needsValues = "ROLE".equals(type) || "USER".equals(type);
            if (needsValues && values.isEmpty()) {
                throw new BizException(1002, "节点「" + name + "」没有配置审批人");
            }
            if ("ROLE".equals(type)) {
                for (String value : values) {
                    if (!ROLES.contains(value.toUpperCase(Locale.ROOT))) {
                        throw new BizException(1002, "审批角色只能是 " + String.join(" / ", ROLES));
                    }
                }
            } else if ("USER".equals(type)) {
                Integer found = jdbcTemplate.queryForObject("""
                        select count(*) from sys_user where username in (%s)
                        """.formatted(placeholders(values.size())), Integer.class, values.toArray());
                if (found == null || found != values.size()) {
                    throw new BizException(1002, "节点「" + name + "」里有账号不存在");
                }
            }
        }
        if (!blank(node.timeoutAction())
                && !TIMEOUT_ACTIONS.contains(node.timeoutAction().trim().toUpperCase(Locale.ROOT))) {
            throw new BizException(1002, "超时动作只能是 " + String.join(" / ", TIMEOUT_ACTIONS));
        }
        boolean container = "CONDITION".equals(kind) || "PARALLEL".equals(kind);
        if (container && blank(node.parentKey())) {
            // 容器自己也可以挂在别的容器下，这里只校验分支标识
        }
        if ("CONDITION".equals(kind)) {
            if (blank(node.conditionField()) || blank(node.conditionOp()) || blank(node.conditionValue())) {
                throw new BizException(1002, "条件节点「" + name + "」必须配齐字段、运算符与比较值");
            }
            String field = node.conditionField().trim().toUpperCase(Locale.ROOT);
            if (!CONDITION_FIELDS.contains(field)) {
                throw new BizException(1002, "条件字段只能是 " + String.join(" / ", CONDITION_FIELDS));
            }
            if (!CONDITION_OPS.contains(node.conditionOp().trim())) {
                throw new BizException(1002, "条件运算符只能是 " + String.join(" ", CONDITION_OPS));
            }
            if (NUMERIC_FIELDS.contains(field)) {
                try {
                    new BigDecimal(node.conditionValue().trim());
                } catch (NumberFormatException error) {
                    throw new BizException(1002, "节点「" + name + "」的比较值必须是数字");
                }
            }
            if ("URGENT".equals(field)
                    && !List.of("true", "false").contains(node.conditionValue().trim().toLowerCase(Locale.ROOT))) {
                throw new BizException(1002, "节点「" + name + "」的是否紧急只能填 true / false");
            }
        } else if (!blank(node.conditionField())) {
            // 审批节点上的「本级条件」也允许，但必须成对出现
            if (blank(node.conditionOp()) || blank(node.conditionValue())) {
                throw new BizException(1002, "节点「" + name + "」的条件缺少运算符或比较值");
            }
        }
        if (!blank(node.branchKey()) && !List.of("TRUE", "FALSE").contains(node.branchKey())
                && !node.branchKey().matches("B\\d+")) {
            throw new BizException(1002, "分支标识只能是 TRUE / FALSE / B0 / B1 ...");
        }
    }

    private static String normalizeKind(String kind) {
        if (blank(kind)) {
            return "APPROVER";
        }
        String value = kind.trim().toUpperCase(Locale.ROOT);
        if (!NODE_KINDS.contains(value)) {
            throw new BizException(1002, "节点类型只能是 " + String.join(" / ", NODE_KINDS));
        }
        return value;
    }

    private static String normalizeType(String type) {
        if (blank(type)) {
            return "ROLE";
        }
        String value = type.trim().toUpperCase(Locale.ROOT);
        if (!APPROVER_TYPES.contains(value)) {
            throw new BizException(1002, "审批人来源只能是 " + String.join(" / ", APPROVER_TYPES));
        }
        return value;
    }

    private static String normalizeChoice(String value, List<String> allowed, String fallback) {
        if (blank(value)) {
            return fallback;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : fallback;
    }

    @Override
    public boolean hasPending(String bizType, long bizId) {
        if (blank(bizType)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from sys_approval_instance
                where biz_type = ? and biz_id = ? and status = 'PENDING'
                """, Integer.class, bizType.trim().toUpperCase(Locale.ROOT), bizId);
        return count != null && count > 0;
    }

    // ---------------------------------------------------------------- 业务开关

    /** 三档强度：免审批 / 只按金额阈值 / 走配置的审批流 */
    public static final List<String> BIZ_MODES = List.of("NONE", "THRESHOLD", "FLOW");

    public record BizRequest(String bizType, String name, String menuPath, String mode, String remark) {
    }

    /**
     * 业务类型当前的审批强度。没登记的类型按 FLOW 处理 —— 老业务本来就是「有流程就走」，
     * 不能因为多了这张配置表，就把它们的审批悄悄关掉。
     */
    @Override
    public String modeOf(String bizType) {
        if (blank(bizType)) {
            return "FLOW";
        }
        List<String> modes = jdbcTemplate.queryForList(
                "select mode from sys_approval_biz where biz_type = ?", String.class,
                bizType.trim().toUpperCase(Locale.ROOT));
        return modes.isEmpty() ? "FLOW" : modes.get(0);
    }

    /**
     * 菜单级的审批开关一览：当前强度、绑定的启用流程、在办实例数，以及「开了但没流程」的提醒。
     */
    public List<Map<String, Object>> bizList() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select b.biz_type as "bizType", b.name, b.menu_path as "menuPath", b.mode, b.remark,
                       to_char(b.updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt", b.updated_by as "updatedBy",
                       (select count(*) from sys_approval_flow f
                         where f.biz_type = b.biz_type and f.enabled = true) as "flowCount",
                       (select count(*) from sys_approval_flow f
                         where f.biz_type = b.biz_type and f.enabled = true
                           and coalesce(jsonb_array_length(f.match_rules), 0) = 0) as "catchAllCount",
                       (select f.id from sys_approval_flow f where f.biz_type = b.biz_type and f.enabled = true
                         order by f.priority desc, f.id limit 1) as "flowId",
                       (select f.code from sys_approval_flow f where f.biz_type = b.biz_type and f.enabled = true
                         order by f.priority desc, f.id limit 1) as "flowCode",
                       (select f.name from sys_approval_flow f where f.biz_type = b.biz_type and f.enabled = true
                         order by f.priority desc, f.id limit 1) as "flowName",
                       (select count(*) from sys_approval_instance i
                         where i.biz_type = b.biz_type and i.status = 'PENDING') as "pendingCount"
                from sys_approval_biz b
                order by b.biz_type
                """);
        for (Map<String, Object> row : rows) {
            String mode = String.valueOf(row.get("mode"));
            long flowCount = row.get("flowCount") == null ? 0 : ((Number) row.get("flowCount")).longValue();
            long catchAll = row.get("catchAllCount") == null
                    ? 0 : ((Number) row.get("catchAllCount")).longValue();
            row.put("flowCount", flowCount);
            row.put("catchAllCount", catchAll);
            row.put("pendingCount", row.get("pendingCount") == null ? 0 : row.get("pendingCount"));
            if ("FLOW".equals(mode) && flowCount == 0) {
                row.put("hint", "已开审批但还没有可用流程，实际不会发起审批");
            } else if ("FLOW".equals(mode) && catchAll == 0) {
                row.put("hint", "已配 " + flowCount + " 条链，但没有兜底流程：不满足任何条件的单据会直接生效");
            } else if ("FLOW".equals(mode)) {
                row.put("hint", "已配 " + flowCount + " 条链，按适用条件命中其中一条");
            } else if ("THRESHOLD".equals(mode)) {
                row.put("hint", "只按金额阈值分级，超阈值需管理员审批");
            } else if ("NONE".equals(mode)) {
                row.put("hint", "提交即生效，不产生审批待办");
            } else {
                row.put("hint", "");
            }
        }
        return rows;
    }

    /** 改某个业务类型的审批强度。菜单路径与名称只是展示用，方便从开关列表直接跳过去 */
    @Transactional
    public Map<String, Object> saveBiz(BizRequest request) {
        if (request == null || blank(request.bizType())) {
            throw new BizException(1002, "请指定业务类型");
        }
        String type = request.bizType().trim().toUpperCase(Locale.ROOT);
        String mode = blank(request.mode()) ? "THRESHOLD" : request.mode().trim().toUpperCase(Locale.ROOT);
        if (!BIZ_MODES.contains(mode)) {
            throw new BizException(1002, "审批强度只能是 " + String.join(" / ", BIZ_MODES));
        }
        String name = blank(request.name()) ? type : request.name().trim();
        jdbcTemplate.update("""
                insert into sys_approval_biz (biz_type, name, menu_path, mode, remark, updated_by)
                values (?, ?, ?, ?, ?, ?)
                on conflict (biz_type) do update
                   set name = excluded.name, menu_path = excluded.menu_path, mode = excluded.mode,
                       remark = excluded.remark, updated_by = excluded.updated_by, updated_at = now()
                """, type, name, blank(request.menuPath()) ? null : request.menuPath().trim(),
                mode, blank(request.remark()) ? null : request.remark().trim(), CurrentUser.name());
        return bizList().stream()
                .filter(row -> type.equals(String.valueOf(row.get("bizType"))))
                .findFirst()
                .orElseThrow(() -> new BizException(4004, "审批开关保存后读取失败"));
    }

    /** 只改适用条件与优先级，不动 BPMN（改流程结构走 saveFlow，会重新部署版本） */
    @Transactional
    public Map<String, Object> saveMatch(long id, Integer priority, String note, List<MatchRule> rules) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, biz_type, enabled, priority from sys_approval_flow where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        Map<String, Object> flow = rows.get(0);
        String bizType = String.valueOf(flow.get("biz_type"));
        List<MatchRule> normalized = normalizeMatchRules(rules);
        int value = priority == null ? 100 : priority;
        if (value < 1 || value > 999) {
            throw new BizException(1002, "优先级请在 1 ~ 999 之间，数字越大越先匹配");
        }
        if (Boolean.TRUE.equals(flow.get("enabled"))) {
            List<Map<String, Object>> others = jdbcTemplate.queryForList("""
                    select code, priority, match_rules as "matchRules"
                    from sys_approval_flow
                    where biz_type = ? and enabled = true and id <> ?
                    """, bizType, id);
            for (Map<String, Object> other : others) {
                int otherPriority = other.get("priority") == null
                        ? 100 : ((Number) other.get("priority")).intValue();
                if (otherPriority == value) {
                    throw new BizException(4042, "业务类型 " + bizType + " 的优先级 " + value
                            + " 已被流程「" + other.get("code") + "」占用");
                }
                List<Map<String, Object>> otherRules = parseRules(other.get("matchRules"));
                if (normalized.isEmpty() && otherRules != null && otherRules.isEmpty()) {
                    throw new BizException(4042, "业务类型 " + bizType + " 已有一条不限条件的兜底流程（"
                            + other.get("code") + "），请给它加适用条件，或把本条改成兜底");
                }
            }
        }
        jdbcTemplate.update("""
                update sys_approval_flow set match_rules = ?::jsonb, priority = ?, match_note = ?
                where id = ?
                """, matchRulesJson(normalized), value, blankToNull(note), id);
        return flow(id);
    }

    // ---------------------------------------------------------------- 流程匹配

    /** 一条适用条件：字段 / 运算符 / 比较值，多条之间是 AND */
    public record MatchRule(String field, String op, String value) {
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 按适用条件挑流程：同业务类型内按优先级从高到低逐条试，第一条命中即用。
     *
     * <p>规则为空表示兜底（catch-all），所以兜底那条要放最低优先级；一条都没命中就没有审批，
     * 并且写一条命中日志，方便回答「这张单为什么没人审」。</p>
     */
    private Map<String, Object> pickFlow(String bizType, long bizId, String bizNo,
                                         Map<String, Object> variables) {
        List<Map<String, Object>> flows = jdbcTemplate.queryForList("""
                select id, code, name, priority, match_rules as "matchRules", match_note as "matchNote",
                       process_version as "processVersion", stable_version as "stableVersion",
                       canary_version as "canaryVersion", canary_percent as "canaryPercent",
                       canary_factory_ids as "canaryFactoryIds", canary_users as "canaryUsers"
                from sys_approval_flow
                where biz_type = ? and enabled = true
                order by priority desc, id asc
                """, bizType);
        for (Map<String, Object> flow : flows) {
            List<Map<String, Object>> rules = parseRules(flow.get("matchRules"));
            if (rules == null) {
                // 规则写坏了就跳过这条，别当成兜底把什么都放进来
                continue;
            }
            if (matchAll(rules, variables)) {
                writeMatchLog(bizType, bizId, bizNo, flow, variables, true,
                        rules.isEmpty() ? "兜底流程（不限条件）" : "命中 " + rules.size() + " 条适用条件");
                return flow;
            }
        }
        writeMatchLog(bizType, bizId, bizNo, null, variables, false, "没有命中任何适用条件");
        return null;
    }

    /** 试算：拿一组假想变量看会走哪条链，只读不落库 */
    public Map<String, Object> simulate(String bizType, Map<String, Object> variables) {
        String type = blank(bizType) ? "" : bizType.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> flows = jdbcTemplate.queryForList("""
                select id, code, name, priority, match_rules as "matchRules", match_note as "matchNote"
                from sys_approval_flow
                where biz_type = ? and enabled = true
                order by priority desc, id asc
                """, type);
        Map<String, Object> vars = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (value != null) {
                    vars.put(key, value);
                    vars.put(key.toUpperCase(Locale.ROOT), value);
                }
            });
        }
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> hit = null;
        for (Map<String, Object> flow : flows) {
            List<Map<String, Object>> rules = parseRules(flow.get("matchRules"));
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("flowId", flow.get("id"));
            trace.put("flowCode", flow.get("code"));
            trace.put("flowName", flow.get("name"));
            trace.put("priority", flow.get("priority"));
            trace.put("matchNote", flow.get("matchNote"));
            trace.put("catchAll", rules != null && rules.isEmpty());
            List<Map<String, Object>> details = new ArrayList<>();
            // 规则解析不出来就是这条链配坏了：不参与匹配，也不当兜底
            boolean passed = rules != null;
            if (rules != null) {
                passed = true;
                for (Map<String, Object> rule : rules) {
                    boolean one = matchOne(rule, vars);
                    Map<String, Object> detail = new LinkedHashMap<>(rule);
                    detail.put("actual", actualOf(rule, vars));
                    detail.put("pass", one);
                    details.add(detail);
                    passed = passed && one;
                }
            }
            trace.put("conditions", details);
            trace.put("pass", passed);
            trace.put("hint", rules == null ? "适用条件格式有误，这条不会生效" : null);
            traces.add(trace);
            if (passed && hit == null) {
                hit = trace;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bizType", type);
        result.put("matched", hit != null);
        result.put("flowCode", hit == null ? null : hit.get("flowCode"));
        result.put("flowName", hit == null ? null : hit.get("flowName"));
        result.put("traces", traces);
        return result;
    }

    private Object actualOf(Map<String, Object> rule, Map<String, Object> variables) {
        String field = ruleField(rule);
        if (field == null) {
            return null;
        }
        Object actual = variables.get(field);
        return actual != null ? actual : variables.get(field.toLowerCase(Locale.ROOT));
    }

    private boolean matchAll(List<Map<String, Object>> rules, Map<String, Object> variables) {
        if (rules.isEmpty()) {
            return true;
        }
        for (Map<String, Object> rule : rules) {
            if (!matchOne(rule, variables)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchOne(Map<String, Object> rule, Map<String, Object> variables) {
        String field = ruleField(rule);
        String op = ruleOp(rule);
        String expected = ruleValue(rule);
        if (field == null || op == null || expected == null || expected.isBlank()) {
            return false;
        }
        Object actual = actualOf(rule, variables);
        if (actual == null) {
            return false;
        }
        if ("IN".equals(op) || "NOT IN".equals(op)) {
            boolean contains = false;
            for (String item : expected.split(",")) {
                if (item.trim().equalsIgnoreCase(String.valueOf(actual).trim())) {
                    contains = true;
                    break;
                }
            }
            return "IN".equals(op) == contains;
        }
        BigDecimal left = decimalOrNull(actual);
        BigDecimal right = decimalOrNull(expected);
        if (left != null && right != null) {
            int cmp = left.compareTo(right);
            return switch (op) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case "=" -> cmp == 0;
                case "!=" -> cmp != 0;
                default -> false;
            };
        }
        boolean same = String.valueOf(actual).trim().equalsIgnoreCase(expected.trim());
        if ("!=".equals(op)) {
            return !same;
        }
        return "=".equals(op) && same;
    }

    /** 解析失败返回 null，调用方据此跳过这条流程，不把坏规则当兜底 */
    private List<Map<String, Object>> parseRules(Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rules = JSON.readValue(text,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
            return rules == null ? List.of() : rules;
        } catch (Exception error) {
            log.warn("审批流适用条件解析失败：{}", error.getMessage());
            return null;
        }
    }

    private String matchRulesJson(List<MatchRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (MatchRule rule : rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", rule.field());
            item.put("op", rule.op());
            item.put("value", rule.value());
            payload.add(item);
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception error) {
            throw new BizException(1002, "适用条件格式不对：" + error.getMessage());
        }
    }

    /** 校验并规范化：字段白名单、运算符白名单、数字字段必须能转数字 */
    private List<MatchRule> normalizeMatchRules(List<MatchRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<MatchRule> normalized = new ArrayList<>();
        for (MatchRule rule : rules) {
            if (rule == null || blank(rule.field()) || blank(rule.op()) || blank(rule.value())) {
                throw new BizException(1002, "适用条件要配齐字段、运算符与比较值");
            }
            String field = rule.field().trim().toUpperCase(Locale.ROOT);
            String op = rule.op().trim().toUpperCase(Locale.ROOT);
            if (!CONDITION_FIELDS.contains(field)) {
                throw new BizException(1002, "适用条件字段只能是 " + String.join(" / ", CONDITION_FIELDS));
            }
            if (!MATCH_OPS.contains(op)) {
                throw new BizException(1002, "适用条件运算符只能是 " + String.join(" ", MATCH_OPS));
            }
            String value = rule.value().trim();
            if (NUMERIC_FIELDS.contains(field) && !"IN".equals(op) && !"NOT IN".equals(op)) {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException error) {
                    throw new BizException(1002, field + " 的比较值必须是数字");
                }
            }
            normalized.add(new MatchRule(field, op, value));
        }
        return normalized;
    }

    private void writeMatchLog(String bizType, long bizId, String bizNo, Map<String, Object> flow,
                               Map<String, Object> variables, boolean matched, String reason) {
        try {
            Map<String, Object> plain = new LinkedHashMap<>();
            variables.forEach((key, value) -> {
                if (value == null || value instanceof Number || value instanceof Boolean
                        || value instanceof String) {
                    plain.put(key, value);
                }
            });
            String vars = JSON.writeValueAsString(plain);
            jdbcTemplate.update("""
                    insert into sys_approval_match_log (biz_type, biz_id, biz_no, flow_id, flow_code,
                                                        variables, matched, reason)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, bizType, bizId, bizNo,
                    flow == null ? null : ((Number) flow.get("id")).longValue(),
                    flow == null ? null : String.valueOf(flow.get("code")), vars, matched, reason);
        } catch (Exception error) {
            log.warn("写审批匹配日志失败：{}", error.getMessage());
        }
    }

    private static String ruleField(Map<String, Object> rule) {
        Object value = rule.get("field");
        return value == null ? null : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String ruleOp(Map<String, Object> rule) {
        Object value = rule.get("op");
        return value == null ? null : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String ruleValue(Map<String, Object> rule) {
        Object value = rule.get("value");
        return value == null ? null : String.valueOf(value).trim();
    }

    private static BigDecimal decimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 提交

    public Long submit(String bizType, long bizId, String bizNo, String title) {
        return submit(bizType, bizId, bizNo, title, Map.of());
    }

    /**
     * 发起审批：按业务类型找启用流程，解析每个节点的审批人写进流程变量，再启动 Flowable 实例。
     *
     * @return Flowable 的 processInstanceId；没有启用流程时返回 null（业务侧按原逻辑降级）
     */
    @Transactional
    @Override
    public Long submit(String bizType, long bizId, String bizNo, String title, Map<String, Object> context) {
        return submitInternal(bizType, bizId, bizNo, title, context, null);
    }

    @Transactional
    @Override
    public Long submitWithFlow(String bizType, long bizId, String bizNo, String title,
                               Long flowId, Map<String, Object> context) {
        return submitInternal(bizType, bizId, bizNo, title, context, flowId);
    }

    /** 只按条件算一次「会命中哪条链」，不启动流程：行级分流先逐行选链再分组 */
    @Override
    public Long matchFlow(String bizType, long bizId, String bizNo, Map<String, Object> variables) {
        String type = blank(bizType) ? "" : bizType.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> vars = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (value != null) {
                    vars.put(key, value);
                    vars.put(key.toUpperCase(Locale.ROOT), value);
                }
            });
        }
        Map<String, Object> flow = pickFlow(type, bizId, bizNo, vars);
        return flow == null ? null : ((Number) flow.get("id")).longValue();
    }

    /** 提交主体：forcedFlowId 为空才走条件匹配；行级分组时由调用方指定链 */
    private Long submitInternal(String bizType, long bizId, String bizNo, String title,
                                Map<String, Object> context, Long forcedFlowId) {
        String type = bizType.trim().toUpperCase(Locale.ROOT);
        // 开关闸门：业务类型没开「走审批流」，这里直接返回 null，调用方按「无需审批」继续
        if (!"FLOW".equals(modeOf(type))) {
            return null;
        }
        String applicant = CurrentUser.name();
        // 变量先攒齐：匹配规则要按金额、类别、是否紧急挑流程，所以不能先定流程再攒变量
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("bizType", type);
        variables.put("bizId", bizId);
        variables.put("bizNo", bizNo);
        variables.put("title", blank(title) ? bizNo : title);
        variables.put("applicant", applicant);
        variables.put("applicantRole", CurrentUser.role());
        // 建模器里可以直接写这些表达式：${initiator}、${initiatorLeader}、${initiatorLeader2}
        variables.put("initiator", applicant);
        String leader1 = leaderOf(applicant, 1);
        String leader2 = leaderOf(applicant, 2);
        variables.put("initiatorLeader", leader1 == null ? applicant : leader1);
        variables.put("initiatorLeader2", leader2 == null
                ? (leader1 == null ? applicant : leader1) : leader2);
        variables.put("adminUser", "admin");
        if (context != null) {
            context.forEach((key, value) -> {
                if (value != null) {
                    variables.put(key, value);
                    variables.put(key.toUpperCase(Locale.ROOT), value);
                }
            });
        }
        variables.putIfAbsent("qty", BigDecimal.ZERO);
        variables.putIfAbsent("amount", BigDecimal.ZERO);

        Map<String, Object> flow;
        if (forcedFlowId == null) {
            // 按适用条件挑流程：命中优先级最高的一条，命中不到就没有审批（留痕可查）
            flow = pickFlow(type, bizId, bizNo, variables);
        } else {
            // 行级分组已经选过链，这里按 id 直接取，避免组内变量变化后跑到另一条链
            List<Map<String, Object>> forced = jdbcTemplate.queryForList("""
                    select id, code, name, priority, match_note as "matchNote",
                           process_version as "processVersion", stable_version as "stableVersion",
                           canary_version as "canaryVersion", canary_percent as "canaryPercent",
                           canary_factory_ids as "canaryFactoryIds", canary_users as "canaryUsers"
                    from sys_approval_flow where id = ? and enabled = true
                    """, forcedFlowId);
            if (forced.isEmpty()) {
                return null;
            }
            flow = forced.get(0);
            writeMatchLog(type, bizId, bizNo, flow, variables, true, "按行分组指定的审批链");
        }
        if (flow == null) {
            return null;
        }
        long flowId = ((Number) flow.get("id")).longValue();
        String processKey = String.valueOf(flow.get("code"));
        long deployed = repositoryService.createProcessDefinitionQuery().processDefinitionKey(processKey).count();
        if (deployed == 0) {
            // 一次都没部署过：说明流程还没保存，业务侧按原逻辑降级
            return null;
        }
        variables.put("flowId", flowId);
        ProcessDefinition definition = pickDefinition(processKey, canaryRuleOf(flow),
                flow.get("stableVersion") == null ? null : ((Number) flow.get("stableVersion")).intValue(),
                applicant, factoryOf(applicant));
        // 审批人配置已经编译进 BPMN 属性，这里只剩「会签集合」需要按人算一次
        ProcessDefinition latest = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).latestVersion().singleResult();
        if (latest != null) {
            prepareMultiInstance(latest, variables, applicant);
        }
        try {
            // 让 Flowable 记住发起人，「我发起的」与撤回校验都靠它
            identityService.setAuthenticatedUserId(applicant);
            ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionId(definition == null ? null : definition.getId())
                    .processDefinitionKey(definition == null ? processKey : null)
                    .businessKey(type + ":" + bizId)
                    .name(variables.get("title").toString())
                    .variables(variables)
                    .start();
            Long instanceId = jdbcTemplate.queryForObject("""
                    insert into sys_approval_instance (flow_id, flow_code, biz_type, biz_id, biz_no, title,
                                                       applicant, status, current_seq, current_node, process_id)
                    values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, '待分配', ?) returning id
                    """, Long.class, flowId, processKey, type, bizId, bizNo,
                    variables.get("title").toString(), applicant, instance.getId());
            // 条件全部不命中时流程会立刻结束，这时不能再设变量
            if (runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instance.getId()).singleResult() != null) {
                runtimeService.setVariable(instance.getId(), "processId", instance.getId());
                noticeActiveTasks(instance.getId());
            }
            return instanceId;
        } catch (FlowableException error) {
            log.warn("启动审批流程失败：{}", error.getMessage());
            throw new BizException(4050, "审批流程启动失败：" + error.getMessage());
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    private List<NodeRequest> nodesFromRows(List<Map<String, Object>> rows) {
        List<NodeRequest> nodes = new ArrayList<>();
        for (Map<String, Object> node : rows) {
            nodes.add(new NodeRequest(null, str(node.get("name")), str(node.get("approverType")),
                    split(str(node.get("approverValues"))), str(node.get("multiMode")),
                    str(node.get("conditionField")), str(node.get("conditionOp")),
                    str(node.get("conditionValue")), split(str(node.get("ccUsers"))),
                    str(node.get("remark")), str(node.get("nodeKind")),
                    str(node.get("parentKey")), str(node.get("branchKey")),
                    node.get("levelUp") == null ? 1 : ((Number) node.get("levelUp")).intValue(),
                    str(node.get("emptyStrategy")),
                    node.get("dedupeSameUser") == null || Boolean.TRUE.equals(node.get("dedupeSameUser")),
                    node.get("allowTransfer") == null || Boolean.TRUE.equals(node.get("allowTransfer")),
                    node.get("allowAddSign") == null || Boolean.TRUE.equals(node.get("allowAddSign")),
                    node.get("timeoutHours") == null ? null : ((Number) node.get("timeoutHours")).intValue(),
                    str(node.get("timeoutAction")), str(node.get("itemKey"))));
        }
        return normalizeNodes(nodes);
    }

    /** 页面上的实例 id 是 sys_approval_instance 的数字主键，这里换成 Flowable 的流程实例 id */
    private String processIdOf(long instanceId) {
        List<String> rows = jdbcTemplate.queryForList("""
                select process_id from sys_approval_instance where id = ?
                """, String.class, instanceId);
        if (rows.isEmpty() || blank(rows.get(0))) {
            throw new BizException(4047, "审批记录不存在");
        }
        return rows.get(0);
    }

    /**
     * 建模器画出来的会签节点：多实例的 collection 写的是 {@code ${mi_<taskId>}}，
     * 这里按节点上的审批人配置把人算出来塞进变量，引擎就能按人各生成一条待办。
     */
    private void prepareMultiInstance(ProcessDefinition definition, Map<String, Object> variables,
                                      String applicant) {
        try {
            org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(definition.getId());
            if (model == null) {
                return;
            }
            for (org.flowable.bpmn.model.Process process : model.getProcesses()) {
                for (org.flowable.bpmn.model.FlowElement element : process.getFlowElements()) {
                    if (!(element instanceof org.flowable.bpmn.model.UserTask task)) {
                        continue;
                    }
                    if (!(task.getLoopCharacteristics()
                            instanceof org.flowable.bpmn.model.MultiInstanceLoopCharacteristics loop)) {
                        continue;
                    }
                    String collection = loop.getInputDataItem();
                    if (blank(collection)) {
                        continue;
                    }
                    String key = collection.replace("${", "").replace("}", "").trim();
                    if (!key.startsWith("mi_")) {
                        continue;
                    }
                    variables.put(key, resolveTaskPeople(task, applicant));
                }
            }
        } catch (RuntimeException error) {
            log.warn("解析会签节点失败：{}", error.getMessage());
        }
    }

    private List<String> resolveTaskPeople(org.flowable.bpmn.model.UserTask task, String applicant) {
        String assignee = task.getAssignee();
        if (!blank(assignee) && assignee.contains("${")) {
            if (assignee.contains("initiatorLeader2")) {
                String leader = leaderOf(applicant, 2);
                return List.of(leader == null ? applicant : leader);
            }
            if (assignee.contains("initiatorLeader")) {
                String leader = leaderOf(applicant, 1);
                return List.of(leader == null ? applicant : leader);
            }
            return List.of(applicant);
        }
        List<String> result = new ArrayList<>();
        if (task.getCandidateUsers() != null) {
            result.addAll(task.getCandidateUsers());
        }
        if (!blank(assignee) && !assignee.contains("${")) {
            result.addAll(split(assignee));
        }
        if (result.isEmpty() && task.getCandidateGroups() != null) {
            result.addAll(resolveAssignees("ROLE", task.getCandidateGroups(), applicant, 1));
        }
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    private List<String> resolveAssignees(String type, List<String> values, String applicant, int levelUp) {
        switch (type) {
            case "USER":
                return values;
            case "INITIATOR":
                return blank(applicant) ? List.of() : List.of(applicant);
            case "ORG_LEADER":
                String leader = leaderOf(applicant, levelUp);
                return leader == null ? List.of() : List.of(leader);
            default:
                if (values.isEmpty()) {
                    return List.of();
                }
                String sql = """
                        select username from sys_user
                        where enabled = true and role in (%s) order by id
                        """.formatted(placeholders(values.size()));
                List<String> users = jdbcTemplate.queryForList(sql, String.class,
                        values.stream().map(item -> item.toUpperCase(Locale.ROOT)).toArray());
                return new ArrayList<>(new LinkedHashSet<>(users));
        }
    }

    private String leaderOf(String username, int levelUp) {
        if (blank(username)) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.parent_id, o.leader from sys_user u
                join sys_org o on o.id = u.org_id
                where u.username = ?
                """, username);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> org = rows.get(0);
        for (int i = 1; i < Math.max(levelUp, 1); i++) {
            Object parentId = org.get("parent_id");
            if (parentId == null) {
                return null;
            }
            List<Map<String, Object>> parents = jdbcTemplate.queryForList("""
                    select id, parent_id, leader from sys_org where id = ?
                    """, ((Number) parentId).longValue());
            if (parents.isEmpty()) {
                return null;
            }
            org = parents.get(0);
        }
        Object leader = org.get("leader");
        if (leader == null || blank(String.valueOf(leader))) {
            return null;
        }
        String name = String.valueOf(leader).trim();
        List<String> accounts = jdbcTemplate.queryForList("""
                select username from sys_user
                where enabled = true and (username = ? or real_name = ?)
                order by (username = ?) desc limit 1
                """, String.class, name, name, name);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    // ---------------------------------------------------------------- 待办与审批

    /** 我的待办：指派给我的 + 我角色能领的；管理员看全部 */
    public List<Map<String, Object>> myTasks() {
        String username = CurrentUser.name();
        String role = CurrentUser.role();
        List<Task> tasks;
        if ("ADMIN".equals(role)) {
            tasks = taskService.createTaskQuery().active().orderByTaskCreateTime().asc().list();
        } else {
            tasks = taskService.createTaskQuery().active()
                    .or()
                    .taskAssignee(username)
                    .taskCandidateUser(username)
                    .taskCandidateGroupIn(List.of(role))
                    .endOr()
                    .orderByTaskCreateTime().asc()
                    .list();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Long> instanceIds = new LinkedHashMap<>();
        for (Task task : tasks) {
            String pid = task.getProcessInstanceId();
            instanceIds.computeIfAbsent(pid, key -> {
                List<Long> rows = jdbcTemplate.queryForList(
                        "select id from sys_approval_instance where process_id = ?", Long.class, key);
                return rows.isEmpty() ? null : rows.get(0);
            });
            Map<String, Object> variables = taskService.getVariables(task.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getId());
            row.put("instanceId", instanceIds.get(pid));
            row.put("processId", pid);
            row.put("nodeName", task.getName());
            row.put("nodeKey", task.getTaskDefinitionKey());
            row.put("assignee", task.getAssignee());
            row.put("approverRole", candidateRole(task.getId()));
            row.put("multiMode", isMultiInstance(task) ? "ALL" : null);
            row.put("status", "PENDING");
            row.put("flowCode", processKeyOf(task.getProcessDefinitionId()));
            row.put("bizType", variables.get("bizType"));
            row.put("bizNo", variables.get("bizNo"));
            row.put("title", variables.get("title"));
            row.put("applicant", variables.get("applicant"));
            row.put("submittedAt", format(task.getCreateTime()));
            result.add(row);
        }
        // 加签待办：挂在原待办旁边，也要出现在待办列表里
        String usernameForExtra = username;
        String sql = "admin".equals(role)
                ? """
                select e.id, e.instance_id, e.task_id, e.node_name, e.assignee, e.added_by,
                       e.created_at, i.id as "instanceId", i.biz_type as "bizType", i.biz_no as "bizNo",
                       i.title, i.applicant
                from sys_approval_extra_task e
                left join sys_approval_instance i on i.process_id = e.instance_id
                where e.status = 'PENDING' order by e.id
                """
                : """
                select e.id, e.instance_id, e.task_id, e.node_name, e.assignee, e.added_by,
                       e.created_at, i.id as "instanceId", i.biz_type as "bizType", i.biz_no as "bizNo",
                       i.title, i.applicant
                from sys_approval_extra_task e
                left join sys_approval_instance i on i.process_id = e.instance_id
                where e.status = 'PENDING' and e.assignee = ? order by e.id
                """;
        List<Map<String, Object>> extras = "admin".equals(role)
                ? jdbcTemplate.queryForList(sql)
                : jdbcTemplate.queryForList(sql, usernameForExtra);
        for (Map<String, Object> extra : extras) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", extra.get("id"));
            row.put("extra", true);
            row.put("instanceId", extra.get("instanceId"));
            row.put("processId", extra.get("instance_id"));
            row.put("nodeName", extra.get("node_name"));
            row.put("taskId", extra.get("task_id"));
            row.put("assignee", extra.get("assignee"));
            row.put("addedBy", extra.get("added_by"));
            row.put("multiMode", null);
            row.put("status", "PENDING");
            row.put("bizType", extra.get("bizType"));
            row.put("bizNo", extra.get("bizNo"));
            row.put("title", extra.get("title"));
            row.put("applicant", extra.get("applicant"));
            row.put("submittedAt", format((Date) extra.get("created_at")));
            result.add(row);
        }
        return result;
    }

    /** 我的待办分页：引擎与加签待办合并排序后按页切片；不传 page 时仍走 myTasks() */
    public PageResult myTasksPage(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> rows = myTasks();
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return new PageResult(new ArrayList<>(rows.subList(from, to)), rows.size(), safePage, safeSize);
    }

    private String processKeyOf(String processDefinitionId) {
        if (blank(processDefinitionId)) {
            return null;
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        return definition == null ? null : definition.getKey();
    }

    private String candidateRole(String taskId) {
        List<IdentityLink> links = taskService.getIdentityLinksForTask(taskId);
        for (IdentityLink link : links) {
            if ("candidate".equals(link.getType()) && link.getGroupId() != null) {
                return link.getGroupId();
            }
        }
        return null;
    }

    /** 会签节点会有多条同名待办，用这个判断当前是不是多实例 */
    private boolean isMultiInstance(Task task) {
        long count = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active().count();
        return count > 1;
    }

    @Transactional
    public Map<String, Object> approve(String taskId, String comment) {
        return approve(taskId, comment, null);
    }

    /**
     * 审批通过。流程开了「必须手写签名」时，没有签名直接拒绝；
     * 有签名就落库，审批记录里能看到签名图片。
     */
    @Transactional
    public Map<String, Object> approve(String taskId, String comment, String signature) {
        Task task = requireTask(taskId);
        Map<String, Object> variables = taskService.getVariables(taskId);
        if (signatureRequired(variables.get("flowId")) && blank(signature)) {
            throw new BizException(1002, "该流程要求手写签名，请先签名再通过");
        }
        if (!blank(signature)) {
            if (!signature.startsWith("data:image/")) {
                throw new BizException(1002, "签名格式不对，应为 data:image/png;base64,...");
            }
            if (signature.length() > 400_000) {
                throw new BizException(1002, "签名图片太大，请重签一次");
            }
            jdbcTemplate.update("""
                    insert into sys_approval_signature (instance_id, task_id, node_key, approver, image)
                    values (?, ?, ?, ?, ?)
                    """, task.getProcessInstanceId(), taskId, task.getTaskDefinitionKey(),
                    CurrentUser.name(), signature);
        }
        return act(taskId, comment, false);
    }

    @Transactional
    public Map<String, Object> reject(String taskId, String comment) {
        if (blank(comment)) {
            throw new BizException(1002, "驳回必须填写原因");
        }
        return act(taskId, comment, true);
    }

    private Map<String, Object> act(String taskId, String comment, boolean reject) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new BizException(4044, "该任务已经处理过");
        }
        assertMine(task);
        Map<String, Object> variables = taskService.getVariables(taskId);
        String pid = task.getProcessInstanceId();
        String nodeName = task.getName();
        if (!blank(comment)) {
            taskService.addComment(taskId, pid, comment);
        }
        if (!reject) {
            Integer pendingExtras = jdbcTemplate.queryForObject("""
                    select count(*) from sys_approval_extra_task
                    where task_id = ? and status = 'PENDING'
                    """, Integer.class, taskId);
            if (pendingExtras != null && pendingExtras > 0) {
                throw new BizException(4048, "还有 " + pendingExtras + " 位加签人没处理，等他们确认后再通过");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        if (reject) {
            runtimeService.deleteProcessInstance(pid, "REJECTED:" + comment);
            syncStatus(pid, "REJECTED", "已驳回");
            applyBusiness(variables, "REJECTED");
            noticeUser(String.valueOf(variables.get("applicant")), "审批被驳回：" + variables.get("title"),
                    "「" + nodeName + "」驳回，意见：" + comment, pid);
            result.put("status", "REJECTED");
            return result;
        }
        taskService.complete(taskId, Map.of("approved", true));
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        if (running == null) {
            syncStatus(pid, "APPROVED", "流程结束");
            applyBusiness(variables, "APPROVED");
            noticeUser(String.valueOf(variables.get("applicant")), "审批通过：" + variables.get("title"),
                    "审批已全部通过，可以继续执行后续业务。", pid);
            result.put("status", "APPROVED");
            return result;
        }
        List<Task> next = taskService.createTaskQuery().processInstanceId(pid).active().list();
        String currentNode = next.isEmpty() ? "流程结束"
                : String.join(" / ", next.stream().map(Task::getName).distinct().toList());
        syncStatus(pid, "PENDING", currentNode);
        logEvent(pid, task.getTaskDefinitionKey(), nodeName, "PASS",
                CurrentUser.name() + " 通过" + (blank(comment) ? "" : "：" + comment), CurrentUser.name());
        // 每个节点的动作都要通知到人：申请人知道进度，下一个节点的审批人收到待办
        noticeUser(String.valueOf(variables.get("applicant")), "审批进度：" + variables.get("title"),
                "「" + nodeName + "」已由 " + CurrentUser.name() + " 通过，当前到「" + currentNode + "」。", pid);
        noticeActiveTasks(pid);
        result.put("status", "PENDING");
        result.put("waiting", next.size());
        if (!next.isEmpty()) {
            result.put("nextNode", currentNode);
        }
        return result;
    }

    /** 转交：把待办改派给另一个人，原处理人不再持有 */
    @Transactional
    public Map<String, Object> transferTask(String taskId, String targetUser, String comment) {
        Task task = requireTask(taskId);
        assertMine(task);
        assertNodeAllows(task, "allow_transfer", "该节点不允许转交，请直接审批或联系管理员");
        String target = requireUser(targetUser, "转交对象");
        taskService.setAssignee(task.getId(), target);
        if (!blank(comment)) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), "转交给 " + target + "：" + comment);
        }
        Map<String, Object> variables = taskService.getVariables(task.getId());
        noticeUser(target, "转交的审批待办：" + variables.get("title"),
                "「" + task.getName() + "」已由 " + CurrentUser.name() + " 转交给你。", task.getProcessInstanceId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "PENDING");
        result.put("assignee", target);
        result.put("message", "已转交给 " + target);
        return result;
    }

    /**
     * 加签：在当前待办旁边挂一条「加签待办」，原审批人要等加签人处理完才能通过；
     * 加签人不同意则整单驳回。这样或签 / 会签节点都能加签，不依赖引擎改多实例。
     */
    @Transactional
    public Map<String, Object> addSign(String taskId, String targetUser, String comment) {
        Task task = requireTask(taskId);
        assertMine(task);
        assertNodeAllows(task, "allow_add_sign", "该节点不允许加签");
        String target = requireUser(targetUser, "加签对象");
        if (target.equals(CurrentUser.name())) {
            throw new BizException(1002, "不能给自己加签");
        }
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from sys_approval_extra_task
                where task_id = ? and assignee = ? and status = 'PENDING'
                """, Integer.class, taskId, target);
        if (exists != null && exists > 0) {
            throw new BizException(1002, "已经给「" + target + "」加过签了，等他处理完再说");
        }
        jdbcTemplate.update("""
                insert into sys_approval_extra_task (instance_id, task_id, node_key, node_name,
                                                     assignee, added_by, status)
                values (?, ?, ?, ?, ?, ?, 'PENDING')
                """, task.getProcessInstanceId(), taskId, task.getTaskDefinitionKey(), task.getName(),
                target, CurrentUser.name());
        Map<String, Object> variables = taskService.getVariables(taskId);
        noticeUser(target, "加签待办：" + variables.get("title"),
                CurrentUser.name() + " 在「" + task.getName() + "」加签了你，单据 " + variables.get("bizNo")
                        + "，请确认后再由原审批人通过。", task.getProcessInstanceId());
        logEvent(task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getName(),
                "ADDSIGN", "加签 " + target + (blank(comment) ? "" : "：" + comment), CurrentUser.name());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "PENDING");
        result.put("message", "已加签 " + target + "，等他处理完你再通过");
        return result;
    }

    /** 加签人通过：只标记这条加签待办，原待办不受影响 */
    @Transactional
    public Map<String, Object> approveExtra(long extraId, String comment) {
        Map<String, Object> extra = loadExtraTask(extraId);
        jdbcTemplate.update("""
                update sys_approval_extra_task set status = 'DONE', action = 'APPROVE',
                       comment = ?, acted_at = now()
                where id = ?
                """, blankToNull(comment), extraId);
        String pid = String.valueOf(extra.get("instance_id"));
        Map<String, Object> variables = variablesOf(pid);
        logEvent(pid, str(extra.get("node_key")), str(extra.get("node_name")), "ADDSIGN_PASS",
                "加签人 " + CurrentUser.name() + " 已确认" + (blank(comment) ? "" : "：" + comment),
                CurrentUser.name());
        Task task = taskService.createTaskQuery().taskId(str(extra.get("task_id"))).singleResult();
        if (task != null && task.getAssignee() != null) {
            noticeUser(task.getAssignee(), "加签人已确认：" + variables.get("title"),
                    "「" + task.getName() + "」的加签人 " + CurrentUser.name() + " 已确认，你可以继续处理。",
                    pid);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", extraId);
        result.put("status", "DONE");
        return result;
    }

    /** 加签人不同意：整单驳回，并通知申请人 */
    @Transactional
    public Map<String, Object> rejectExtra(long extraId, String comment) {
        Map<String, Object> extra = loadExtraTask(extraId);
        if (blank(comment)) {
            throw new BizException(1002, "加签人不同意必须填原因");
        }
        jdbcTemplate.update("""
                update sys_approval_extra_task set status = 'DONE', action = 'REJECT',
                       comment = ?, acted_at = now()
                where id = ?
                """, comment, extraId);
        String pid = String.valueOf(extra.get("instance_id"));
        Map<String, Object> variables = variablesOf(pid);
        runtimeService.deleteProcessInstance(pid, "REJECTED:" + comment);
        syncStatus(pid, "REJECTED", "加签人驳回");
        applyBusiness(variables, "REJECTED");
        noticeUser(String.valueOf(variables.get("applicant")), "审批被驳回：" + variables.get("title"),
                "加签人 " + CurrentUser.name() + " 不同意，意见：" + comment, pid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", extraId);
        result.put("status", "REJECTED");
        return result;
    }

    private Map<String, Object> loadExtraTask(long extraId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, instance_id, task_id, node_key, node_name, assignee, status
                from sys_approval_extra_task where id = ?
                """, extraId);
        if (rows.isEmpty()) {
            throw new BizException(4047, "加签待办不存在");
        }
        Map<String, Object> extra = rows.get(0);
        if (!"PENDING".equals(String.valueOf(extra.get("status")))) {
            throw new BizException(4044, "这条加签已经处理过了");
        }
        boolean admin = "ADMIN".equals(CurrentUser.role());
        if (!admin && !CurrentUser.name().equals(String.valueOf(extra.get("assignee")))) {
            throw new BizException(403, "这条加签不是给你的");
        }
        return extra;
    }

    /** 催办：给当前节点所有待办人推提醒 */
    @Transactional
    public Map<String, Object> urge(long instanceId) {
        String pid = processIdOf(instanceId);
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        if (instance == null) {
            throw new BizException(4047, "审批记录不存在");
        }
        boolean admin = "ADMIN".equals(CurrentUser.role());
        if (!admin && !CurrentUser.name().equals(instance.getStartUserId())) {
            throw new BizException(403, "只有申请人或管理员可以催办");
        }
        Map<String, Object> variables = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(pid).list().stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getVariableName(), item.getValue()),
                        LinkedHashMap::putAll);
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pid).active().list();
        if (tasks.isEmpty()) {
            throw new BizException(4045, "该审批已结束，无需催办");
        }
        Set<String> notified = new LinkedHashSet<>();
        for (Task task : tasks) {
            String content = CurrentUser.name() + " 催促处理「" + task.getName() + "」，单据 "
                    + variables.get("bizNo") + "。";
            if (task.getAssignee() != null) {
                noticeUser(task.getAssignee(), "审批催办：" + variables.get("title"), content, pid);
                notified.add(task.getAssignee());
                continue;
            }
            for (IdentityLink link : taskService.getIdentityLinksForTask(task.getId())) {
                if (!"candidate".equals(link.getType())) {
                    continue;
                }
                if (link.getUserId() != null) {
                    noticeUser(link.getUserId(), "审批催办：" + variables.get("title"), content, pid);
                    notified.add(link.getUserId());
                } else if (link.getGroupId() != null) {
                    notice.pushToRoles(List.of(link.getGroupId()), NoticeMessage.of("TASK",
                            "审批催办：" + variables.get("title"), content, "APPROVAL", pid));
                    notified.add(link.getGroupId());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("notified", notified.size());
        result.put("message", notified.isEmpty() ? "当前节点没有待办人" : "已催办 " + notified.size() + " 人");
        return result;
    }

    /** 申请人撤回：只有还没人处理时允许 */
    @Transactional
    @Override
    public void cancel(long instanceId) {
        String pid = processIdOf(instanceId);
        ProcessInstance running = runtimeService.createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (running == null) {
            throw new BizException(4045, "该审批已结束，不能撤回");
        }
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        boolean admin = "ADMIN".equals(CurrentUser.role());
        if (instance != null && !admin && !CurrentUser.name().equals(instance.getStartUserId())) {
            throw new BizException(403, "只有申请人可以撤回");
        }
        long finished = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(pid).finished().count();
        if (finished > 0) {
            throw new BizException(4046, "已有节点审批通过，不能撤回");
        }
        Map<String, Object> variables = variablesOf(pid);
        runtimeService.deleteProcessInstance(pid, "CANCELLED");
        syncStatus(pid, "CANCELLED", "已撤回");
        applyBusiness(variables, "CANCELLED");
    }

    /** 业务侧提交后确认状态：流程已结束且是正常结束就算已通过 */
    @Override
    public String statusOf(long instanceId) {
        String pid = processIdOf(instanceId);
        if (runtimeService.createProcessInstanceQuery().processInstanceId(pid).singleResult() != null) {
            return "PENDING";
        }
        return statusOfInstance(pid);
    }

    private String statusOfInstance(String pid) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        if (instance == null) {
            return null;
        }
        if (instance.getEndTime() == null) {
            return "PENDING";
        }
        String reason = instance.getDeleteReason();
        if (reason == null) {
            return "APPROVED";
        }
        if (reason.startsWith("REJECTED")) {
            return "REJECTED";
        }
        return "CANCELLED";
    }

    /** 把 Flowable 的最终状态同步到我们的实例索引表，列表页就不用每次去问引擎 */
    private void syncStatus(String pid, String status, String currentNode) {
        jdbcTemplate.update("""
                update sys_approval_instance
                   set status = ?, current_node = ?, finished_at = case when ? = 'PENDING' then null else now() end
                 where process_id = ?
                """, status, currentNode, status, pid);
    }

    public PageResult instances(String keyword, String status, Boolean mine, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder("""
                 where (title ilike ? or coalesce(biz_no, '') ilike ? or applicant ilike ?)
                """);
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        if (!blank(status)) {
            where.append(" and status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(mine)) {
            where.append(" and applicant = ?");
            args.add(CurrentUser.name());
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_approval_instance " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select id, flow_code as "flowCode", biz_type as "bizType", biz_id as "bizId",
                       biz_no as "bizNo", title, applicant, status,
                       current_node as "currentNode",
                       to_char(submitted_at, 'YYYY-MM-DD HH24:MI') as "submittedAt",
                       to_char(finished_at, 'YYYY-MM-DD HH24:MI') as "finishedAt",
                       process_id as "processId"
                from sys_approval_instance
                """ + where + " order by id desc limit ? offset ?", pageArgs.toArray());
        for (Map<String, Object> row : records) {
            Object pid = row.get("processId");
            if (pid != null && "PENDING".equals(String.valueOf(row.get("status")))) {
                row.put("currentNode", currentNode(String.valueOf(pid)));
            }
            row.remove("processId");
        }
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    private String currentNode(String pid) {
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pid).active().list();
        if (tasks.isEmpty()) {
            return "流程结束";
        }
        return String.join(" / ", tasks.stream().map(Task::getName).distinct().toList());
    }

    public Map<String, Object> detail(long instanceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, flow_code as "flowCode", biz_type as "bizType", biz_id as "bizId",
                       biz_no as "bizNo", title, applicant, status,
                       current_node as "currentNode",
                       to_char(submitted_at, 'YYYY-MM-DD HH24:MI') as "submittedAt",
                       to_char(finished_at, 'YYYY-MM-DD HH24:MI') as "finishedAt",
                       process_id as "processId"
                from sys_approval_instance where id = ?
                """, instanceId);
        if (rows.isEmpty()) {
            throw new BizException(4047, "审批记录不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        String pid = String.valueOf(detail.remove("processId"));
        if (blank(pid) || "null".equals(pid)) {
            detail.put("tasks", List.of());
            return detail;
        }
        if ("PENDING".equals(String.valueOf(detail.get("status")))) {
            detail.put("currentNode", currentNode(pid));
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<HistoricTaskInstance> historyTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(pid).orderByHistoricTaskInstanceStartTime().asc().list();
        for (HistoricTaskInstance task : historyTasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getId());
            row.put("nodeName", task.getName());
            row.put("nodeKey", task.getTaskDefinitionKey());
            row.put("assignee", task.getAssignee());
            row.put("multiMode", null);
            row.put("status", task.getEndTime() == null ? "PENDING" : "DONE");
            row.put("approver", task.getAssignee());
            row.put("action", "APPROVE");
            row.put("comment", commentOf(task.getId()));
            row.put("actedAt", task.getEndTime() == null ? "待处理" : format(task.getEndTime()));
            tasks.add(row);
        }
        jdbcTemplate.queryForList("""
                select node_name as "nodeName", action, comment, operator,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "actedAt"
                from sys_approval_log where instance_id = ? order by id
                """, pid).forEach(logRow -> {
            Map<String, Object> row = new LinkedHashMap<>(logRow);
            row.put("status", "DONE");
            row.put("approver", "system");
            tasks.add(row);
        });
        detail.put("tasks", tasks);
        detail.put("signatures", jdbcTemplate.queryForList("""
                select task_id as "taskId", approver, image,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "signedAt"
                from sys_approval_signature where instance_id = ? order by id
                """, pid));
        return detail;
    }

    private String commentOf(String taskId) {
        List<org.flowable.engine.task.Comment> comments =
                taskService.getTaskComments(taskId, "comment");
        return comments.isEmpty() ? null : comments.get(comments.size() - 1).getFullMessage();
    }

    /**
     * 超时扫描：待办超过节点配置的时间后，提醒或自动通过。
     * 由定时任务每小时调用，也可以手工触发。
     */
    @Transactional
    @Override
    public TimeoutScan scanTimeouts() {
        int reminded = 0;
        int autoPassed = 0;
        List<Task> tasks = taskService.createTaskQuery().active().list();
        for (Task task : tasks) {
            Map<String, Object> node = nodeConfigOf(task);
            if (node == null || node.get("timeoutHours") == null) {
                continue;
            }
            int hours = ((Number) node.get("timeoutHours")).intValue();
            Date created = task.getCreateTime();
            if (created == null) {
                continue;
            }
            Instant deadline = created.toInstant().plus(Duration.ofHours(hours));
            if (Instant.now().isBefore(deadline)) {
                continue;
            }
            Map<String, Object> variables = taskService.getVariables(task.getId());
            if ("AUTO_PASS".equals(str(node.get("timeoutAction")))) {
                taskService.addComment(task.getId(), task.getProcessInstanceId(),
                        "超时 " + hours + " 小时未处理，系统自动通过");
                taskService.complete(task.getId(), Map.of("approved", true));
                logEvent(task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getName(),
                        "AUTO", "超时自动通过", "system");
                if (runtimeService.createProcessInstanceQuery()
                        .processInstanceId(task.getProcessInstanceId()).singleResult() == null) {
                    syncStatus(task.getProcessInstanceId(), "APPROVED", "流程结束");
                    applyBusiness(variables, "APPROVED");
                } else {
                    syncStatus(task.getProcessInstanceId(), "PENDING",
                            currentNode(task.getProcessInstanceId()));
                }
                autoPassed++;
            } else {
                Integer logged = jdbcTemplate.queryForObject("""
                        select count(*) from sys_approval_log
                        where instance_id = ? and node_key = ? and action = 'REMIND'
                        """, Integer.class, task.getProcessInstanceId(), task.getTaskDefinitionKey());
                if (logged != null && logged > 0) {
                    continue;
                }
                String content = "「" + task.getName() + "」已超时 " + hours + " 小时，请尽快处理。";
                if (task.getAssignee() != null) {
                    noticeUser(task.getAssignee(), "审批超时提醒：" + variables.get("title"),
                            content, task.getProcessInstanceId());
                } else {
                    String role = candidateRole(task.getId());
                    if (role != null) {
                        notice.pushToRoles(List.of(role), NoticeMessage.of("TASK",
                                "审批超时提醒：" + variables.get("title"), content, "APPROVAL",
                                task.getProcessInstanceId()));
                    }
                }
                logEvent(task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getName(),
                        "REMIND", "超时提醒", "system");
                reminded++;
            }
        }
        return new TimeoutScan(tasks.size(), reminded, autoPassed);
    }

    /** 转交 / 加签时可选的人员，普通审批人也能读 */
    public List<Map<String, Object>> assigneeOptions(String keyword) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return jdbcTemplate.queryForList("""
                select username, coalesce(real_name, username) as "realName", role
                from sys_user
                where enabled = true and (username ilike ? or coalesce(real_name, '') ilike ?)
                order by username limit 200
                """, like, like);
    }

    // ---------------------------------------------------------------- 回退 / 灰度 / 字段权限 / 签名

    /** 可做字段级权限的单据字段（按业务类型） */
    public static final List<String> TRANSFER_FIELD_KEYS =
            List.of("fromWarehouse", "toWarehouse", "totalQty", "plannedDate", "remark");

    private static final Map<String, String> FIELD_LABELS = Map.of(
            "fromWarehouse", "源仓库",
            "toWarehouse", "目标仓库",
            "totalQty", "调拨数量",
            "plannedDate", "计划日期",
            "remark", "备注");

    /** 只读字段（数量、仓库不允许在审批里改） */
    private static final Set<String> READ_ONLY_FIELDS = Set.of("fromWarehouse", "toWarehouse", "totalQty");

    /**
     * 回退到之前走过的某个节点：把当前执行移回去，重新生成那一级的待办。
     * 加签待办随当前节点一起作废。
     */
    @Transactional
    public Map<String, Object> rollback(String taskId, String targetNodeKey, String comment) {
        Task task = requireTask(taskId);
        assertMine(task);
        if (blank(targetNodeKey)) {
            throw new BizException(1002, "请选择要回退到的节点");
        }
        String pid = task.getProcessInstanceId();
        String currentKey = task.getTaskDefinitionKey();
        if (targetNodeKey.equals(currentKey)) {
            throw new BizException(1002, "已经停在这个节点了，不用回退");
        }
        List<String> passed = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(pid).finished().list().stream()
                .map(HistoricTaskInstance::getTaskDefinitionKey).distinct().toList();
        if (!passed.contains(targetNodeKey)) {
            throw new BizException(1002, "只能回退到本单已经走过的节点");
        }
        jdbcTemplate.update("""
                update sys_approval_extra_task set status = 'DONE', action = 'SKIP',
                       comment = '节点回退，加签作废', acted_at = now()
                where task_id = ? and status = 'PENDING'
                """, taskId);
        if (!blank(comment)) {
            taskService.addComment(taskId, pid, "回退至 " + targetNodeKey + "：" + comment);
        }
        try {
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(pid)
                    .moveActivityIdTo(currentKey, targetNodeKey)
                    .changeState();
        } catch (FlowableException error) {
            throw new BizException(4052, "当前节点不支持回退（可能是并行分支或多实例）：" + error.getMessage());
        }
        String nodeName = currentNode(pid);
        syncStatus(pid, "PENDING", nodeName);
        Map<String, Object> variables = variablesOf(pid);
        logEvent(pid, currentKey, task.getName(), "ROLLBACK",
                CurrentUser.name() + " 回退到 " + targetNodeKey + (blank(comment) ? "" : "：" + comment),
                CurrentUser.name());
        noticeUser(String.valueOf(variables.get("applicant")), "审批被退回：" + variables.get("title"),
                "「" + task.getName() + "」被 " + CurrentUser.name() + " 退回，当前在「" + nodeName + "」。", pid);
        noticeActiveTasks(pid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PENDING");
        result.put("currentNode", nodeName);
        result.put("message", "已回退到「" + nodeName + "」");
        return result;
    }

    /** 当前节点可以回退到哪些节点（走过且不是当前节点） */
    public List<Map<String, Object>> rollbackTargets(String taskId) {
        Task task = requireTask(taskId);
        String pid = task.getProcessInstanceId();
        Map<String, String> names = new LinkedHashMap<>();
        for (HistoricTaskInstance item : historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(pid).finished().orderByHistoricTaskInstanceStartTime().asc().list()) {
            if (item.getTaskDefinitionKey() == null
                    || item.getTaskDefinitionKey().equals(task.getTaskDefinitionKey())) {
                continue;
            }
            names.putIfAbsent(item.getTaskDefinitionKey(), item.getName());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        names.forEach((key, name) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeKey", key);
            row.put("nodeName", name);
            result.add(row);
        });
        return result;
    }

    /**
     * 灰度发布（老签名，保留给已有调用方）：按比例分流到指定版本。
     * percent=0 或 version 为空表示关闭灰度。
     */
    @Transactional
    public Map<String, Object> configureCanary(long flowId, Integer percent, Integer version) {
        return configureCanary(flowId, new CanaryRequest(version, percent));
    }

    /**
     * 灰度发布：定向名单里的人一定走灰度版本，名单外的人再按比例抽。
     *
     * <p>规则优先级：定向命中（按人 / 按工厂）→ 直接灰度；否则按 percent 随机；
     * percent=0 表示只放给定向名单。version 为空即关闭灰度，全部回到稳定版本。</p>
     */
    @Transactional
    public Map<String, Object> configureCanary(long flowId, CanaryRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select code, process_version, stable_version, canary_version, canary_factory_ids,
                       canary_users, canary_note
                from sys_approval_flow where id = ?
                """, flowId);
        if (rows.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        Map<String, Object> flow = rows.get(0);
        String code = String.valueOf(flow.get("code"));
        int percent = request.percent() == null ? 0 : request.percent();
        List<Long> factoryIds = normalizeIds(request.factoryOrgIds());
        List<String> usernames = normalizeNames(request.usernames());

        if (request.version() == null || (percent <= 0 && factoryIds.isEmpty() && usernames.isEmpty())) {
            jdbcTemplate.update("""
                    update sys_approval_flow set canary_percent = 0, canary_version = null,
                           canary_factory_ids = null, canary_users = null, canary_note = null,
                           canary_updated_at = now(),
                           stable_version = coalesce(stable_version, process_version)
                    where id = ?
                    """, flowId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("canaryPercent", 0);
            result.put("canaryVersion", null);
            result.put("stableVersion", stableVersionOf(flow));
            result.put("message", "已关闭灰度，全部走稳定版本");
            return result;
        }
        if (percent > 100) {
            throw new BizException(1002, "灰度比例只能是 0-100");
        }
        if (percent == 0 && factoryIds.isEmpty() && usernames.isEmpty()) {
            throw new BizException(1002, "灰度比例为 0 时，至少要指定一个定向工厂或定向用户");
        }
        long exists = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(code).processDefinitionVersion(request.version()).count();
        if (exists == 0) {
            throw new BizException(1002, "版本 v" + request.version() + " 不存在，先保存一次流程产生该版本");
        }
        String factories = factoryIds.isEmpty() ? null : String.join(",", factoryIds.stream().map(String::valueOf).toList());
        String users = usernames.isEmpty() ? null : String.join(",", usernames);
        jdbcTemplate.update("""
                update sys_approval_flow set canary_percent = ?, canary_version = ?,
                       canary_factory_ids = ?, canary_users = ?, canary_note = ?,
                       canary_updated_at = now(),
                       stable_version = coalesce(stable_version, process_version)
                where id = ?
                """, percent, request.version(), factories, users,
                request.note() == null || request.note().isBlank() ? null : request.note().trim(), flowId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canaryPercent", percent);
        result.put("canaryVersion", request.version());
        result.put("canaryFactoryIds", factoryIds);
        result.put("canaryUsers", usernames);
        result.put("canaryNote", request.note());
        result.put("stableVersion", stableVersionOf(flow));
        result.put("message", canarySummary(request.version(), percent, factoryIds, usernames));
        return result;
    }

    /** 灰度现状 + 各版本实际跑了多少单，用于判断能不能放量 */
    public Map<String, Object> canaryStatus(long flowId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select code, process_version, stable_version, canary_version, canary_percent,
                       canary_factory_ids, canary_users, canary_note,
                       to_char(canary_updated_at, 'YYYY-MM-DD HH24:MI') as "canaryUpdatedAt"
                from sys_approval_flow where id = ?
                """, flowId);
        if (rows.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        Map<String, Object> flow = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canaryVersion", flow.get("canary_version"));
        result.put("canaryPercent", flow.get("canary_percent") == null ? 0 : flow.get("canary_percent"));
        result.put("canaryFactoryIds", splitIds(flow.get("canary_factory_ids")));
        result.put("canaryUsers", splitNames(flow.get("canary_users")));
        result.put("canaryNote", flow.get("canary_note"));
        result.put("canaryUpdatedAt", flow.get("canaryUpdatedAt"));
        result.put("stableVersion", stableVersionOf(flow));
        result.put("processVersion", flow.get("process_version"));
        result.put("active", flow.get("canary_version") != null);
        // 每个流程版本实际落了多少单：灰度版跑得怎么样，看这里最直接
        result.put("versions", jdbcTemplate.queryForList("""
                select pd.version_ as "version",
                       count(*) as "total",
                       count(*) filter (where i.status = 'PENDING') as "pending",
                       count(*) filter (where i.status = 'APPROVED') as "approved",
                       count(*) filter (where i.status in ('REJECTED', 'WITHDRAWN')) as "abnormal"
                from sys_approval_instance i
                join act_hi_procinst p on p.proc_inst_id_ = i.process_id
                join act_re_procdef pd on pd.id_ = p.proc_def_id_
                where i.flow_code = ?
                group by pd.version_ order by pd.version_ desc
                """, String.valueOf(flow.get("code"))));
        return result;
    }

    private String stableVersionOf(Map<String, Object> flow) {
        Object stable = flow.get("stable_version");
        Object current = flow.get("process_version");
        Object value = stable == null ? current : stable;
        return value == null ? null : String.valueOf(value);
    }

    private String canarySummary(Integer version, int percent,
                                 List<Long> factoryIds, List<String> usernames) {
        List<String> parts = new ArrayList<>();
        if (!factoryIds.isEmpty()) {
            parts.add(factoryIds.size() + " 个工厂");
        }
        if (!usernames.isEmpty()) {
            parts.add(usernames.size() + " 个用户");
        }
        StringBuilder text = new StringBuilder("灰度已开启：v").append(version);
        if (!parts.isEmpty()) {
            text.append("，定向 ").append(String.join(" + ", parts)).append(" 全量走新版本");
        }
        if (percent > 0) {
            text.append(parts.isEmpty() ? "，按 " : "，其余按 ").append(percent).append("% 随机分流");
        }
        return text.toString();
    }

    private List<Long> normalizeIds(List<Long> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private List<String> normalizeNames(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private List<Long> splitIds(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String item : String.valueOf(raw).split(",")) {
            String text = item.trim();
            if (!text.isEmpty()) {
                try {
                    result.add(Long.valueOf(text));
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过，不影响其它定向项
                }
            }
        }
        return result;
    }

    private List<String> splitNames(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : String.valueOf(raw).split(",")) {
            String text = item.trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    private CanaryRule canaryRuleOf(Map<String, Object> flow) {
        Object version = flow.get("canaryVersion");
        if (version == null) {
            return CanaryRule.none();
        }
        int percent = flow.get("canaryPercent") == null ? 0 : ((Number) flow.get("canaryPercent")).intValue();
        return new CanaryRule(((Number) version).intValue(), percent,
                Set.copyOf(splitIds(flow.get("canaryFactoryIds"))),
                Set.copyOf(splitNames(flow.get("canaryUsers"))));
    }

    /** 发起人所属工厂：用于按工厂定向灰度，取不到时返回 null（只按人与比例判断） */
    private Long factoryOf(String username) {
        try {
            return orgScope.factoryOf(username);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 提交时挑流程定义。
     *
     * <p>定向命中（按人 / 按工厂）直接走灰度版；没命中再看比例随机；
     * 两者都没有就走稳定版，稳定版没配就用最新版。</p>
     */
    private ProcessDefinition pickDefinition(String processKey, CanaryRule rule, Integer stableVersion,
                                             String applicant, Long factoryOrgId) {
        if (rule.active()) {
            boolean hit = rule.matches(applicant, factoryOrgId)
                    || (rule.percent() > 0
                        && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < rule.percent());
            if (hit) {
                ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionKey(processKey).processDefinitionVersion(rule.version()).singleResult();
                if (definition != null) {
                    return definition;
                }
            }
        }
        if (stableVersion != null) {
            ProcessDefinition stable = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey).processDefinitionVersion(stableVersion).singleResult();
            if (stable != null) {
                return stable;
            }
        }
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).latestVersion().singleResult();
    }

    public List<Map<String, Object>> fieldRules(long flowId) {
        return jdbcTemplate.queryForList("""
                select node_key as "nodeKey", field_key as "fieldKey", access
                from sys_approval_field_rule where flow_id = ? order by node_key, field_key
                """, flowId);
    }

    /** 从已部署的 BPMN 里读用户任务，供「按节点配字段权限」的下拉用 */
    public List<Map<String, Object>> userTasks(long flowId) {
        List<String> codes = jdbcTemplate.queryForList(
                "select code from sys_approval_flow where id = ?", String.class, flowId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (codes.isEmpty()) {
            return result;
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(codes.get(0)).latestVersion().singleResult();
        if (definition == null) {
            return result;
        }
        org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(definition.getId());
        for (org.flowable.bpmn.model.Process process : model.getProcesses()) {
            for (org.flowable.bpmn.model.FlowElement element : process.getFlowElements()) {
                if (element instanceof org.flowable.bpmn.model.UserTask task) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("nodeKey", task.getId());
                    row.put("nodeName", task.getName());
                    result.add(row);
                }
            }
        }
        return result;
    }

    @Transactional
    public void saveFieldRules(long flowId, List<Map<String, String>> rules) {
        jdbcTemplate.update("delete from sys_approval_field_rule where flow_id = ?", flowId);
        if (rules == null) {
            return;
        }
        for (Map<String, String> rule : rules) {
            String nodeKey = rule.get("nodeKey");
            String fieldKey = rule.get("fieldKey");
            String access = rule.getOrDefault("access", "VIEW").toUpperCase(Locale.ROOT);
            if (blank(nodeKey) || blank(fieldKey)) {
                continue;
            }
            if (!TRANSFER_FIELD_KEYS.contains(fieldKey)) {
                throw new BizException(1002, "未知字段 " + fieldKey);
            }
            if (!List.of("VIEW", "EDIT", "HIDDEN").contains(access)) {
                throw new BizException(1002, "字段权限只能是 VIEW / EDIT / HIDDEN");
            }
            if (READ_ONLY_FIELDS.contains(fieldKey) && "EDIT".equals(access)) {
                throw new BizException(1002, "「" + FIELD_LABELS.get(fieldKey) + "」不允许在审批里修改");
            }
            jdbcTemplate.update("""
                    insert into sys_approval_field_rule (flow_id, node_key, field_key, access)
                    values (?, ?, ?, ?)
                    on conflict (flow_id, node_key, field_key) do update set access = excluded.access
                    """, flowId, nodeKey, fieldKey, access);
        }
    }

    /** 当前待办对应的表单：按节点字段权限过滤，隐藏字段不返回 */
    public Map<String, Object> taskForm(String taskId) {
        Task task = requireTask(taskId);
        Map<String, Object> variables = taskService.getVariables(taskId);
        String bizType = str(variables.get("bizType"));
        Object flowId = variables.get("flowId");
        Map<String, String> access = fieldAccess(flowId, task.getTaskDefinitionKey());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeKey", task.getTaskDefinitionKey());
        result.put("nodeName", task.getName());
        result.put("bizType", bizType);
        result.put("signatureRequired", signatureRequired(flowId));
        if (!"TRANSFER".equals(bizType)) {
            result.put("fields", List.of());
            return result;
        }
        long bizId = ((Number) variables.get("bizId")).longValue();
        Map<String, Object> order;
        try {
            // 调拨单归仓储：摘要（含两个仓库名与明细合计）由仓储的契约给
            TransferOrderPort.TransferSummary transfer = transferOrders.summary(bizId);
            order = new LinkedHashMap<>();
            order.put("remark", transfer.remark());
            order.put("plannedDate", transfer.plannedDate() == null ? null : transfer.plannedDate().toString());
            order.put("fromWarehouse", transfer.fromWarehouse());
            order.put("toWarehouse", transfer.toWarehouse());
            order.put("totalQty", transfer.totalQty());
        } catch (RuntimeException error) {
            throw new BizException(4047, "单据不存在");
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String key : TRANSFER_FIELD_KEYS) {
            String level = access.getOrDefault(key, "VIEW");
            if ("HIDDEN".equals(level)) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("key", key);
            field.put("label", FIELD_LABELS.get(key));
            field.put("value", order.get(key));
            field.put("access", level);
            field.put("readOnly", READ_ONLY_FIELDS.contains(key) || !"EDIT".equals(level));
            fields.add(field);
        }
        result.put("fields", fields);
        return result;
    }

    /** 保存审批人改过的字段（只接受 EDIT 权限的字段） */
    @Transactional
    public Map<String, Object> saveTaskForm(String taskId, Map<String, String> values) {
        Task task = requireTask(taskId);
        assertMine(task);
        Map<String, Object> variables = taskService.getVariables(taskId);
        if (!"TRANSFER".equals(str(variables.get("bizType")))) {
            throw new BizException(1002, "该单据不支持在审批里修改字段");
        }
        Object flowId = variables.get("flowId");
        Map<String, String> access = fieldAccess(flowId, task.getTaskDefinitionKey());
        long bizId = ((Number) variables.get("bizId")).longValue();
        List<String> changed = new ArrayList<>();
        if (values == null) {
            values = Map.of();
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (READ_ONLY_FIELDS.contains(key)) {
                continue;
            }
            if (!"EDIT".equals(access.getOrDefault(key, "VIEW"))) {
                throw new BizException(403, "当前节点没有修改「" + FIELD_LABELS.getOrDefault(key, key) + "」的权限");
            }
            if ("remark".equals(key)) {
                transferOrders.updateRemark(bizId, entry.getValue());
                changed.add("备注");
            } else if ("plannedDate".equals(key)) {
                String date = blank(entry.getValue()) ? null : entry.getValue().trim();
                try {
                    transferOrders.updatePlannedDate(bizId, date == null ? null : LocalDate.parse(date));
                } catch (RuntimeException error) {
                    throw new BizException(1002, "计划日期格式应为 YYYY-MM-DD");
                }
                changed.add("计划日期");
            }
        }
        if (!changed.isEmpty()) {
            logEvent(task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getName(), "FORM",
                    CurrentUser.name() + " 修改了 " + String.join("、", changed), CurrentUser.name());
            noticeUser(String.valueOf(variables.get("applicant")), "审批中修改了单据：" + variables.get("title"),
                    CurrentUser.name() + " 在「" + task.getName() + "」修改了" + String.join("、", changed) + "。",
                    task.getProcessInstanceId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changed", changed);
        return result;
    }

    private Map<String, String> fieldAccess(Object flowId, String nodeKey) {
        Map<String, String> result = new LinkedHashMap<>();
        if (flowId == null || blank(nodeKey)) {
            return result;
        }
        jdbcTemplate.queryForList("""
                select field_key, access from sys_approval_field_rule
                where flow_id = ? and node_key = ?
                """, ((Number) flowId).longValue(), nodeKey)
                .forEach(row -> result.put(String.valueOf(row.get("field_key")),
                        String.valueOf(row.get("access"))));
        return result;
    }

    private boolean signatureRequired(Object flowId) {
        if (flowId == null) {
            return false;
        }
        List<Boolean> rows = jdbcTemplate.queryForList(
                "select signature_required from sys_approval_flow where id = ?", Boolean.class,
                ((Number) flowId).longValue());
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    @Transactional
    public void setSignatureRequired(long flowId, boolean required) {
        jdbcTemplate.update("update sys_approval_flow set signature_required = ? where id = ?",
                required, flowId);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 待办所在节点的业务策略（超时、允许转交 / 加签、抄送人）。
     *
     * <p>这些是 BPMN 表达不了的业务规则，单独存放在 {@code sys_approval_node_policy}，
     * 按 flow_id + node_key 与 BPMN 里的用户任务对应。节点清单本身从 BPMN 读，
     * 所以不存在「画布改了、节点表没改」的两套事实问题。</p>
     */
    private Map<String, Object> nodeConfigOf(Task task) {
        try {
            Object flowId = runtimeService.getVariable(task.getProcessInstanceId(), "flowId");
            if (flowId == null) {
                return null;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select timeout_hours as "timeoutHours", timeout_action as "timeoutAction",
                           allow_transfer as "allowTransfer", allow_add_sign as "allowAddSign",
                           cc_users as "ccUsers"
                    from sys_approval_node_policy where flow_id = ? and node_key = ?
                    """, ((Number) flowId).longValue(), task.getTaskDefinitionKey());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException error) {
            log.warn("读取节点策略失败：{}", error.getMessage());
            return null;
        }
    }

    /** 节点策略清单：节点来自 BPMN，策略来自策略表，没有配的返回空值 */
    public List<Map<String, Object>> nodePolicies(long flowId) {
        List<String> codes = jdbcTemplate.queryForList(
                "select code from sys_approval_flow where id = ?", String.class, flowId);
        if (codes.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        Map<String, Map<String, Object>> saved = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                select node_key as "nodeKey", timeout_hours as "timeoutHours",
                       timeout_action as "timeoutAction", allow_transfer as "allowTransfer",
                       allow_add_sign as "allowAddSign", cc_users as "ccUsers"
                from sys_approval_node_policy where flow_id = ?
                """, flowId).forEach(row -> saved.put(String.valueOf(row.get("nodeKey")), row));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : bpmnNodes(codes.get(0))) {
            String key = String.valueOf(node.get("itemKey"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeKey", key);
            row.put("nodeName", node.get("name"));
            row.put("nodeKind", node.get("nodeKind"));
            row.put("multiMode", node.get("multiMode"));
            Map<String, Object> policy = saved.get(key);
            row.put("timeoutHours", policy == null ? null : policy.get("timeoutHours"));
            row.put("timeoutAction", policy == null ? null : policy.get("timeoutAction"));
            row.put("allowTransfer", policy == null ? null : policy.get("allowTransfer"));
            row.put("allowAddSign", policy == null ? null : policy.get("allowAddSign"));
            row.put("ccUsers", policy == null ? null : policy.get("ccUsers"));
            result.add(row);
        }
        return result;
    }

    /** 节点策略入参 */
    public record NodePolicyRequest(String nodeKey, Integer timeoutHours, String timeoutAction,
                                    Boolean allowTransfer, Boolean allowAddSign, String ccUsers) {
    }

    /**
     * 保存节点策略：整表覆盖该流程的策略（没配的行等于恢复默认）。
     * nodeKey 必须是 BPMN 里真实存在的用户任务，避免画布改名后留下一堆孤儿配置。
     */
    @Transactional
    public int saveNodePolicies(long flowId, List<NodePolicyRequest> requests) {
        List<String> codes = jdbcTemplate.queryForList(
                "select code from sys_approval_flow where id = ?", String.class, flowId);
        if (codes.isEmpty()) {
            throw new BizException(4041, "审批流不存在");
        }
        Map<String, String> nodeNames = new LinkedHashMap<>();
        for (Map<String, Object> node : bpmnNodes(codes.get(0))) {
            nodeNames.put(String.valueOf(node.get("itemKey")), String.valueOf(node.get("name")));
        }
        jdbcTemplate.update("delete from sys_approval_node_policy where flow_id = ?", flowId);
        int saved = 0;
        for (NodePolicyRequest request : requests == null ? List.<NodePolicyRequest>of() : requests) {
            if (request == null || blank(request.nodeKey())) {
                continue;
            }
            String key = request.nodeKey().trim();
            if (!nodeNames.containsKey(key)) {
                throw new BizException(1002, "节点 " + key + " 不在当前流程里，请先保存画布");
            }
            Integer hours = request.timeoutHours() == null || request.timeoutHours() <= 0
                    ? null : request.timeoutHours();
            String action = blank(request.timeoutAction()) ? null
                    : normalizeChoice(request.timeoutAction(), TIMEOUT_ACTIONS, null);
            String cc = String.join(",", cleanValues(split(request.ccUsers())));
            if (hours == null && action == null && request.allowTransfer() == null
                    && request.allowAddSign() == null && cc.isEmpty()) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into sys_approval_node_policy (flow_id, node_key, node_name, timeout_hours,
                                                          timeout_action, allow_transfer, allow_add_sign,
                                                          cc_users)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, flowId, key, nodeNames.get(key), hours, action,
                    request.allowTransfer(), request.allowAddSign(), cc.isEmpty() ? null : cc);
            saved++;
        }
        return saved;
    }

    private void assertNodeAllows(Task task, String column, String message) {
        Map<String, Object> node = nodeConfigOf(task);
        if (node == null) {
            return;
        }
        String key = "allowTransfer".equals(column) || "allow_transfer".equals(column)
                ? "allowTransfer" : "allowAddSign";
        if (Boolean.FALSE.equals(node.get(key))) {
            throw new BizException(4049, message);
        }
    }

    private Task requireTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new BizException(4044, "该任务已经处理过");
        }
        return task;
    }

    private void assertMine(Task task) {
        boolean admin = "ADMIN".equals(CurrentUser.role());
        if (admin) {
            return;
        }
        String username = CurrentUser.name();
        if (task.getAssignee() != null) {
            if (!task.getAssignee().equals(username)) {
                throw new BizException(403, "该任务指定给「" + task.getAssignee() + "」处理");
            }
            return;
        }
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        for (IdentityLink link : links) {
            if ("candidate".equals(link.getType())) {
                if (username.equals(link.getUserId()) || CurrentUser.role().equals(link.getGroupId())) {
                    return;
                }
            }
        }
        throw new BizException(403, "该节点不是你能处理的");
    }

    private String requireUser(String username, String label) {
        if (blank(username)) {
            throw new BizException(1002, label + "不能为空");
        }
        String target = username.trim();
        Integer found = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ?", Integer.class, target);
        if (found == null || found == 0) {
            throw new BizException(1002, label + "「" + target + "」不存在");
        }
        return target;
    }

    private Map<String, Object> variablesOf(String pid) {
        Map<String, Object> result = new LinkedHashMap<>();
        historyService.createHistoricVariableInstanceQuery().processInstanceId(pid).list()
                .forEach(item -> result.put(item.getVariableName(), item.getValue()));
        return result;
    }

    private void logEvent(String pid, String nodeKey, String nodeName, String action,
                          String comment, String operator) {
        jdbcTemplate.update("""
                insert into sys_approval_log (instance_id, node_key, node_name, action, comment, operator)
                values (?, ?, ?, ?, ?, ?)
                """, pid, nodeKey, nodeName, action, comment, operator);
    }

    private void noticeUser(String username, String title, String content, String pid) {
        if (blank(username)) {
            return;
        }
        notice.push(username, NoticeMessage.of("TASK", title, content, "APPROVAL", pid));
    }

    /** 给当前所有在途待办的处理人推一条「待办到达」 */
    private void noticeActiveTasks(String pid) {
        Map<String, Object> variables = variablesOf(pid);
        String title = str(variables.get("title"));
        String bizNo = str(variables.get("bizNo"));
        for (Task task : taskService.createTaskQuery().processInstanceId(pid).active().list()) {
            String content = "单据 " + bizNo + " 到「" + task.getName() + "」，等待处理。";
            if (task.getAssignee() != null) {
                noticeUser(task.getAssignee(), "审批待办：" + title, content, pid);
                continue;
            }
            for (IdentityLink link : taskService.getIdentityLinksForTask(task.getId())) {
                if (!"candidate".equals(link.getType())) {
                    continue;
                }
                if (link.getUserId() != null) {
                    noticeUser(link.getUserId(), "审批待办：" + title, content, pid);
                } else if (link.getGroupId() != null) {
                    notice.pushToRoles(List.of(link.getGroupId()), NoticeMessage.of("TASK",
                            "审批待办：" + title, content, "APPROVAL", pid));
                }
            }
        }
    }

    /** 审批结果回写业务单据：加新业务类型时在这里加一个分支 */
    private void applyBusiness(Map<String, Object> variables, String outcome) {
        Object bizType = variables.get("bizType");
        Object bizId = variables.get("bizId");
        if (bizType == null || bizId == null) {
            return;
        }
        if ("TRANSFER".equalsIgnoreCase(String.valueOf(bizType))) {
            String status = switch (outcome) {
                case "APPROVED" -> "APPROVED";
                case "REJECTED" -> "REJECTED";
                default -> "DRAFT";
            };
            // 状态回写交给仓储：单据状态机归它
            transferOrders.updateStatus(((Number) bizId).longValue(), status);
        } else if ("PERFORMANCE".equalsIgnoreCase(String.valueOf(bizType))) {
            // 绩效单归成本核算：结果回写走成本的窄接口
            performanceApproval.applyApproval(((Number) bizId).longValue(), outcome);
        } else if ("REQUISITION".equalsIgnoreCase(String.valueOf(bizType))) {
            // 采购申请：行级分流时一个实例覆盖一组明细行，回写要带上是哪几行
            Object lineIds = variables.get("lineIds");
            requisitionApproval.applyApproval(((Number) bizId).longValue(),
                    lineIds == null ? null : String.valueOf(lineIds), outcome);
        } else if ("BOM".equalsIgnoreCase(String.valueOf(bizType))) {
            // BOM 版本：通过后自动启用并停用旧版本，驳回/撤回归草稿
            bomApproval.applyApproval(((Number) bizId).longValue(), outcome);
        }
        fireWebhook(variables, outcome);
    }

    /** 自定义逻辑：流程结束 / 驳回 / 撤回时回调流程上配置的地址 */
    private void fireWebhook(Map<String, Object> variables, String outcome) {
        Object flowId = variables.get("flowId");
        if (flowId == null) {
            return;
        }
        List<String> urls = jdbcTemplate.queryForList(
                "select webhook_url from sys_approval_flow where id = ?", String.class,
                ((Number) flowId).longValue());
        if (urls.isEmpty() || blank(urls.get(0))) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome);
        payload.put("bizType", variables.get("bizType"));
        payload.put("bizId", variables.get("bizId"));
        payload.put("bizNo", variables.get("bizNo"));
        payload.put("title", variables.get("title"));
        payload.put("applicant", variables.get("applicant"));
        payload.put("at", java.time.OffsetDateTime.now().toString());
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception error) {
            json = "{}";
        }
        boolean ok = approvalWebhook.post(urls.get(0), payload, json);
        logEvent(String.valueOf(variables.get("processId")), null, null, "WEBHOOK",
                (ok ? "回调成功：" : "回调失败：") + urls.get(0), "system");
    }

    private long countRunning(String code) {
        return runtimeService.createProcessInstanceQuery().processDefinitionKey(code).count();
    }

    private long countAllInstances(String code) {
        return historyService.createHistoricProcessInstanceQuery().processDefinitionKey(code).count();
    }

    private static String format(Date date) {
        return date == null ? null : MINUTE.format(date.toInstant());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && !"null".equals(trimmed) && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> cleanValues(Collection<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (!blank(value) && !result.contains(value.trim())) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}
