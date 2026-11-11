package com.mes.approval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把画布上的节点树编译成 BPMN 2.0 XML，交给 Flowable 执行。
 * <p>
 * 节点树：每个节点带 {@code itemKey}、{@code parentKey}（所属容器）、{@code branchKey}（哪条支路）。
 * 容器两类：
 * <ul>
 *   <li>CONDITION：exclusiveGateway + 汇聚网关，TRUE / FALSE 两条支路，FALSE 走默认流；</li>
 *   <li>PARALLEL：parallelGateway 分叉 + 汇聚，每条支路并发执行，汇齐才继续。</li>
 * </ul>
 * 审批人直接编译进 XML：按角色给 {@code candidateGroups}、按人给 {@code candidateUsers}、
 * 发起人与主管用 {@code ${initiator}} / {@code ${initiatorLeader}}；
 * 只有「按角色会签」这种需要运行时查人的场景才调 {@code approvalHooks}。
 * <p>
 * <b>不再依赖任何节点变量</b>（以前的 {@code list_x / users_x / groups_x / assignee_x}）。
 * 这是把 {@code sys_approval_node} 整表删掉的关键：审批人配置编译进 BPMN 后，
 * 运行时只认 BPMN，画布与节点表不会再有第二份事实。
 * <p>
 * 超时、允许转交 / 加签、抄送人这些业务策略 BPMN 表达不了，单独存在
 * {@code sys_approval_node_policy}（按 flow_id + node_key），与「字段权限」表同构。
 */
public final class BpmnBuilder {

    /** 参与编译的节点（已经按 seq 排好） */
    public record FlowNode(String key, String kind, String name, String parentKey, String branchKey,
                           String approverType, String multiMode, List<String> approverValues,
                           Integer levelUp, String emptyStrategy, String conditionField,
                           String conditionOp, String conditionValue) {
    }

    private final String processKey;
    private final StringBuilder xml = new StringBuilder();
    private final List<String> flows = new ArrayList<>();
    private final Map<String, List<FlowNode>> byContainer = new LinkedHashMap<>();
    private int flowSeq = 0;

    private BpmnBuilder(String processKey) {
        this.processKey = processKey;
    }

    public static String build(String processKey, String processName, List<FlowNode> nodes) {
        BpmnBuilder builder = new BpmnBuilder(processKey);
        return builder.compile(processName, nodes);
    }

    private String compile(String processName, List<FlowNode> nodes) {
        for (FlowNode node : nodes) {
            byContainer.computeIfAbsent(containerOf(node), key -> new ArrayList<>()).add(node);
        }
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n");
        xml.append("             xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("             xmlns:flowable=\"http://flowable.org/bpmn\"\n");
        xml.append("             targetNamespace=\"http://mes.local/approval\">\n");
        xml.append("  <process id=\"").append(escape(processKey)).append("\" name=\"")
                .append(escape(processName)).append("\" isExecutable=\"true\">\n");
        xml.append("    <startEvent id=\"start\" name=\"发起\"/>\n");

        List<FlowNode> root = byContainer.getOrDefault("root", List.of());
        String last = emit(root, "start", null, null, false, "end");
        xml.append("    <endEvent id=\"end\" name=\"结束\"/>\n");
        flows.add(flow(last, "end", null, false));

        flows.forEach(item -> xml.append("    ").append(item).append('\n'));
        xml.append("  </process>\n</definitions>\n");
        return xml.toString();
    }

    private static String containerOf(FlowNode node) {
        if (node.parentKey() == null || node.parentKey().isBlank()) {
            return "root";
        }
        return node.parentKey() + "#" + (node.branchKey() == null ? "" : node.branchKey());
    }

    /**
     * 顺序发射一串节点。
     *
     * @param from         上游元素
     * @param firstFlowId  首条连线的 id（条件分支要用固定 id 才能被 default 引用）
     * @param condition    首条连线上的条件表达式
     * @param firstDefault 首条连线是否为默认流
     * @param tail         本串走完后（或整串为空时）要连到的下游元素
     * @return 最后一个元素的 id
     */
    private String emit(List<FlowNode> list, String from, String firstFlowId, String condition,
                        boolean firstDefault, String tail) {
        String prev = from;
        boolean first = true;
        for (int index = 0; index < list.size(); index++) {
            FlowNode node = list.get(index);
            String id = node.key();
            String nextId = index + 1 < list.size() ? list.get(index + 1).key() : tail;
            String incomingId = first ? firstFlowId : null;
            String incomingCondition = first ? condition : null;
            boolean incomingDefault = first && firstDefault;
            switch (node.kind()) {
                case "CC", "AUTO" -> {
                    String expression = "CC".equals(node.kind())
                            ? "${approvalHooks.notifyCc(execution)}"
                            : "${approvalHooks.autoPass(execution)}";
                    append("    <serviceTask id=\"" + escape(id) + "\" name=\"" + escape(node.name())
                            + "\" flowable:expression=\"" + expression + "\"/>\n");
                    flows.add(flow(prev, id, incomingId, incomingDefault, incomingCondition));
                    prev = id;
                }
                case "CONDITION" -> {
                    String join = id + "_join";
                    append("    <exclusiveGateway id=\"" + escape(id) + "\" name=\"" + escape(node.name())
                            + "\" default=\"" + escape(id) + "_f\"/>\n");
                    append("    <exclusiveGateway id=\"" + escape(join) + "\"/>\n");
                    flows.add(flow(prev, id, incomingId, incomingDefault, incomingCondition));
                    String expression = conditionExpression(node);
                    List<FlowNode> yes = byContainer.getOrDefault(id + "#TRUE", List.of());
                    List<FlowNode> no = byContainer.getOrDefault(id + "#FALSE", List.of());
                    if (yes.isEmpty()) {
                        flows.add(flow(id, join, id + "_t", false, expression));
                    } else {
                        String yesTail = emit(yes, id, id + "_t", expression, false, join);
                        flows.add(flow(yesTail, join, null, false));
                    }
                    if (no.isEmpty()) {
                        flows.add(flow(id, join, id + "_f", true, null));
                    } else {
                        String noTail = emit(no, id, id + "_f", null, true, join);
                        flows.add(flow(noTail, join, null, false));
                    }
                    prev = join;
                }
                case "PARALLEL" -> {
                    String fork = id + "_fork";
                    String join = id + "_join";
                    append("    <parallelGateway id=\"" + escape(fork) + "\" name=\""
                            + escape(node.name()) + "\"/>\n");
                    // 汇聚用包容网关：分支里可能有人被跳过，平行网关会因为「入线数」对不上卡死
                    append("    <inclusiveGateway id=\"" + escape(join) + "\"/>\n");
                    flows.add(flow(prev, fork, incomingId, incomingDefault, incomingCondition));
                    List<String> branches = new ArrayList<>();
                    for (String container : byContainer.keySet()) {
                        if (container.startsWith(id + "#")) {
                            branches.add(container.substring(id.length() + 1));
                        }
                    }
                    branches.remove("TRUE");
                    branches.remove("FALSE");
                    if (branches.isEmpty()) {
                        flows.add(flow(fork, join, null, false));
                    } else {
                        for (String branch : branches) {
                            String branchTail = emit(byContainer.get(id + "#" + branch), fork,
                                    null, null, false, join);
                            flows.add(flow(branchTail, join, null, false));
                        }
                    }
                    prev = join;
                }
                default -> {
                    // 审批节点：前面挂一个「有没有审批人」的网关，解析不到人就整节点跳过
                    String check = id + "_chk";
                    if (node.conditionField() != null && !node.conditionField().isBlank()) {
                        // 本级条件：成立才进本节点，不成立直接跳下游 —— 否则画布上配了条件却没有效果
                        String cond = id + "_cond";
                        append("    <exclusiveGateway id=\"" + escape(cond) + "\" default=\""
                                + escape(cond) + "_skip\"/>\n");
                        flows.add(flow(prev, cond, incomingId, incomingDefault, incomingCondition));
                        flows.add(flow(cond, nextId, cond + "_skip", true));
                        prev = cond;
                        incomingId = null;
                        incomingDefault = false;
                        incomingCondition = conditionExpression(node);
                    }
                    append("    <exclusiveGateway id=\"" + escape(check) + "\" default=\""
                            + escape(id) + "_skip\"/>\n");
                    append(userTask(node));
                    flows.add(flow(prev, check, incomingId, incomingDefault, incomingCondition));
                    flows.add(flow(check, id, id + "_go", false, hasApproverExpression(node)));
                    flows.add(flow(check, nextId, id + "_skip", true));
                    prev = id;
                }
            }
            first = false;
        }
        return prev;
    }

    /** 审批节点：审批人直接编译进属性，或签挂候选人，会签挂多实例集合 */
    private String userTask(FlowNode node) {
        String id = escape(node.key());
        String name = escape(node.name());
        String type = node.approverType() == null ? "ROLE" : node.approverType().toUpperCase(Locale.ROOT);
        List<String> values = node.approverValues() == null ? List.of() : node.approverValues();
        int level = node.levelUp() == null || node.levelUp() < 1 ? 1 : node.levelUp();
        boolean multi = "ALL".equalsIgnoreCase(node.multiMode());
        boolean adminFallback = "ADMIN".equalsIgnoreCase(node.emptyStrategy());
        String typeArgs = "'" + type + "', '" + String.join(",", values) + "'";

        // 会签：谁参与。collection 必须是集合，所以统一用钩子转成 List
        String collection = adminFallback
                ? "${approvalHooks.approverList(" + typeArgs + ")}"
                : switch (type) {
                    case "USER" -> "${approvalHooks.asList('" + String.join(",", values) + "')}";
                    case "INITIATOR" -> "${approvalHooks.asList(initiator)}";
                    case "ORG_LEADER" -> level >= 2
                            ? "${approvalHooks.asList(initiatorLeader2)}"
                            : "${approvalHooks.asList(initiatorLeader)}";
                    // 按角色会签要运行时查人
                    default -> "${approvalHooks.roleUsers('" + roleList(values) + "')}";
                };

        StringBuilder builder = new StringBuilder("    <userTask id=\"").append(id)
                .append("\" name=\"").append(name).append('"');
        if (multi) {
            builder.append(" flowable:assignee=\"${assignee_").append(id).append("}\"");
        } else if (adminFallback) {
            builder.append(" flowable:candidateUsers=\"${approvalHooks.approverUsers(")
                    .append(typeArgs).append(")}\"");
        } else {
            switch (type) {
                case "USER" -> builder.append(" flowable:candidateUsers=\"")
                        .append(escape(String.join(",", values))).append('"');
                case "INITIATOR" -> builder.append(" flowable:assignee=\"${initiator}\"");
                case "ORG_LEADER" -> builder.append(" flowable:assignee=\"")
                        .append(level >= 2 ? "${initiatorLeader2}" : "${initiatorLeader}").append('"');
                default -> builder.append(" flowable:candidateGroups=\"")
                        .append(escape(roleList(values))).append('"');
            }
        }
        if (!multi) {
            builder.append("/>\n");
            return builder.toString();
        }
        builder.append(">\n")
                .append("      <multiInstanceLoopCharacteristics isSequential=\"false\" ")
                .append("flowable:collection=\"").append(escape(collection))
                .append("\" flowable:elementVariable=\"assignee_").append(id).append("\">\n")
                .append("        <completionCondition>")
                .append("${nrOfCompletedInstances == nrOfInstances}")
                .append("</completionCondition>\n")
                .append("      </multiInstanceLoopCharacteristics>\n")
                .append("    </userTask>\n");
        return builder.toString();
    }

    /** 「这个节点有没有审批人」：没有就整节点跳过，等价于「审批人为空自动通过」 */
    private String hasApproverExpression(FlowNode node) {
        // 配了「转管理员」兜底时一定有人可审，不需要跳过
        if ("ADMIN".equalsIgnoreCase(node.emptyStrategy())) {
            return "${true}";
        }
        String type = node.approverType() == null ? "ROLE" : node.approverType().toUpperCase(Locale.ROOT);
        List<String> values = node.approverValues() == null ? List.of() : node.approverValues();
        String args = switch (type) {
            case "USER" -> "'USER', '" + String.join(",", values) + "'";
            case "INITIATOR", "ORG_LEADER" -> "'ALWAYS', ''";
            default -> "'ROLE', '" + roleList(values) + "'";
        };
        return "${approvalHooks.hasApprover(" + args + ")}";
    }

    private static String roleList(List<String> values) {
        return String.join(",", values.stream()
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .toList());
    }

    private void append(String fragment) {
        xml.append(fragment);
    }

    private String flow(String from, String to, String fixedId, boolean isDefault) {
        return flow(from, to, fixedId, isDefault, null);
    }

    private String flow(String from, String to, String fixedId, boolean isDefault, String condition) {
        String id = fixedId != null ? fixedId : "f" + (++flowSeq);
        StringBuilder builder = new StringBuilder();
        builder.append("<sequenceFlow id=\"").append(escape(id)).append("\" sourceRef=\"")
                .append(escape(from)).append("\" targetRef=\"").append(escape(to)).append('"');
        if (isDefault) {
            // 默认流由网关的 default 属性引用，这里不再加条件
            builder.append("/>");
            return builder.toString();
        }
        if (condition == null || condition.isBlank()) {
            builder.append("/>");
            return builder.toString();
        }
        builder.append(">\n      <conditionExpression xsi:type=\"tFormalExpression\"><![CDATA[")
                .append(condition).append("]]></conditionExpression>\n    </sequenceFlow>");
        return builder.toString();
    }

    /** 条件编译成 JUEL 表达式，字段名对齐 submit 时传的流程变量 */
    static String conditionExpression(FlowNode node) {
        String field = node.conditionField() == null ? "" : node.conditionField().trim().toUpperCase(Locale.ROOT);
        String variable = switch (field) {
            case "AMOUNT" -> "amount";
            case "QTY" -> "qty";
            case "ITEM_COUNT" -> "itemCount";
            case "CATEGORY" -> "category";
            case "DEPARTMENT" -> "department";
            case "SOURCE_TYPE" -> "sourceType";
            case "URGENT" -> "urgent";
            case "APPLICANT_ROLE" -> "applicantRole";
            case "BIZ_TYPE" -> "bizType";
            default -> null;
        };
        if (variable == null) {
            return "${true}";
        }
        String op = node.conditionOp() == null ? "=" : node.conditionOp().trim();
        String value = node.conditionValue() == null ? "" : node.conditionValue().trim();
        boolean text = "APPLICANT_ROLE".equals(field) || "BIZ_TYPE".equals(field)
                || "CATEGORY".equals(field) || "DEPARTMENT".equals(field) || "SOURCE_TYPE".equals(field);
        if ("URGENT".equals(field)) {
            // 布尔变量：按 true / false 编译，写成字符串比较在 JUEL 里永远为假
            String literal = "true".equalsIgnoreCase(value) ? "true" : "false";
            return "${" + variable + ("!=".equals(op) ? " != " : " == ") + literal + "}";
        }
        if (text) {
            if ("!=".equals(op)) {
                return "${" + variable + " != '" + value + "'}";
            }
            return "${" + variable + " == '" + value + "'}";
        }
        if ("!=".equals(op)) {
            return "${" + variable + " != " + value + "}";
        }
        if ("=".equals(op)) {
            return "${" + variable + " == " + value + "}";
        }
        return "${" + variable + " " + op + " " + value + "}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
