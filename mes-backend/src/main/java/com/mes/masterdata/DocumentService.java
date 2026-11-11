package com.mes.masterdata;

import com.mes.common.BizException;
import com.mes.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 产品文档：SOP 作业指导书、SIP 检验指导书、图纸与包装规范。
 * 产品级文档对整张工单生效，带 process_id 的文档只对某道工序生效。
 */
@Service
public class DocumentService {

    private static final Set<String> TYPES = Set.of("SOP", "SIP", "DRAWING", "PACKING", "OTHER");

    private final JdbcTemplate jdbcTemplate;

    public DocumentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record DocumentRequest(@NotBlank String docType,
                                  @NotBlank String title,
                                  String version,
                                  Long processId,
                                  String content,
                                  String fileUrl,
                                  Boolean enabled) {
    }

    public List<Map<String, Object>> list(long productId, String docType) {
        StringBuilder sql = new StringBuilder("""
                select d.id, d.product_id as "productId", d.process_id as "processId",
                       d.doc_type as "docType", d.title, d.version, d.content, d.file_url as "fileUrl",
                       d.enabled, d.created_by as "createdBy",
                       to_char(d.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       p.name as "processName"
                from product_document d
                left join rm_process p on p.id = d.process_id
                where d.product_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(productId);
        if (docType != null && !docType.isBlank()) {
            sql.append(" and d.doc_type = ? ");
            args.add(docType.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" order by d.doc_type, d.process_id nulls first, d.id desc");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 工单视角：产品级文档 + 当前工序的指导书，扫码报工时直接可查 */
    public Map<String, Object> forWorkOrder(long workOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select product_id from rm_work_order where id = ?
                """, workOrderId);
        if (rows.isEmpty()) {
            throw new BizException(2016, "工单不存在");
        }
        long productId = ((Number) rows.get(0).get("product_id")).longValue();
        List<Map<String, Object>> docs = list(productId, null);
        List<Map<String, Object>> productDocs = new ArrayList<>();
        Map<Long, Map<String, Object>> byProcess = new LinkedHashMap<>();
        for (Map<String, Object> doc : docs) {
            if (doc.get("processId") == null) {
                productDocs.add(doc);
            } else {
                long processId = ((Number) doc.get("processId")).longValue();
                byProcess.computeIfAbsent(processId, key -> {
                    Map<String, Object> group = new LinkedHashMap<>();
                    group.put("processId", key);
                    group.put("processName", doc.get("processName"));
                    group.put("documents", new ArrayList<Map<String, Object>>());
                    return group;
                });
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items =
                        (List<Map<String, Object>>) byProcess.get(processId).get("documents");
                items.add(doc);
            }
        }
        List<Map<String, Object>> operations = jdbcTemplate.queryForList("""
                select seq, name, process_id as "processId" from rm_work_order_operation
                where work_order_id = ? order by seq
                """, workOrderId);
        List<Map<String, Object>> operationDocs = new ArrayList<>();
        for (Map<String, Object> op : operations) {
            Object pid = op.get("processId");
            Map<String, Object> group = pid == null ? null : byProcess.get(((Number) pid).longValue());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("seq", op.get("seq"));
            line.put("operationName", op.get("name"));
            line.put("processId", pid);
            line.put("documents", group == null ? new ArrayList<>() : group.get("documents"));
            operationDocs.add(line);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("productDocuments", productDocs);
        result.put("operations", operationDocs);
        return result;
    }

    @Transactional
    public long create(long productId, DocumentRequest request) {
        assertProduct(productId);
        String docType = normalizeType(request.docType());
        String title = requireTitle(request.title());
        checkProcess(request.processId());
        Long id = jdbcTemplate.queryForObject("""
                insert into product_document (product_id, process_id, doc_type, title, version,
                                              content, file_url, enabled, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, productId, request.processId(), docType, title,
                normalizeVersion(request.version()), trim(request.content()), trim(request.fileUrl()),
                request.enabled() == null || request.enabled(), CurrentUser.name());
        return id == null ? 0 : id;
    }

    @Transactional
    public void update(long id, DocumentRequest request) {
        load(id);
        String docType = normalizeType(request.docType());
        String title = requireTitle(request.title());
        checkProcess(request.processId());
        jdbcTemplate.update("""
                update product_document set process_id = ?, doc_type = ?, title = ?, version = ?,
                       content = ?, file_url = ?, enabled = ?
                where id = ?
                """, request.processId(), docType, title, normalizeVersion(request.version()),
                trim(request.content()), trim(request.fileUrl()),
                request.enabled() == null || request.enabled(), id);
    }

    public void delete(long id) {
        load(id);
        jdbcTemplate.update("delete from product_document where id = ?", id);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, product_id as \"productId\", title from product_document where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(2005, "文档不存在");
        }
        return rows.get(0);
    }

    private void assertProduct(long productId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from product where id = ?", Integer.class, productId);
        if (count == null || count == 0) {
            throw new BizException(2005, "产品不存在");
        }
    }

    private void checkProcess(Long processId) {
        if (processId == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from rm_process where id = ?", Integer.class, processId);
        if (count == null || count == 0) {
            throw new BizException(2005, "工序不存在");
        }
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "文档类型只能是 SOP、SIP、图纸、包装规范或其他");
        }
        return type;
    }

    private String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写文档标题");
        }
        return value.trim();
    }

    private String normalizeVersion(String value) {
        return value == null || value.isBlank() ? "V1.0" : value.trim();
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
