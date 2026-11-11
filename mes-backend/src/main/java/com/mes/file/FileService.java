package com.mes.file;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.platform.audit.OperLogService;
import com.mes.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一附件入口：先落存储，再写台账，两个都成功才算上传成功。
 * 台账记录存储类型与 key，下载按记录读，因此切换存储配置不影响历史文件。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** 单文件上限 20MB，与 multipart 配置一致 */
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final DateTimeFormatter DAY_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    /** 可执行脚本一律不收，避免附件变成投递入口 */
    private static final Set<String> BLOCKED_EXT = Set.of(
            "exe", "bat", "cmd", "com", "ps1", "sh", "jsp", "jspx", "php", "vbs", "js", "jar", "msi");

    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final CodeGenerator codeGenerator;
    private final OperLogService operLogService;

    public FileService(JdbcTemplate jdbcTemplate, StorageService storageService,
                       CodeGenerator codeGenerator, OperLogService operLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
        this.codeGenerator = codeGenerator;
        this.operLogService = operLogService;
    }

    public record Download(String fileName, String contentType, byte[] data) {
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file, String bizType, Long bizId, String bizNo) {
        if (file == null || file.isEmpty()) {
            throw new BizException(1002, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(1002, "单个附件不能超过 20MB，当前 " + sizeText(file.getSize()));
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException(1002, "读取上传文件失败：" + e.getMessage());
        }
        if (data.length == 0) {
            throw new BizException(1002, "上传内容为空");
        }

        String originalName = cleanName(file.getOriginalFilename());
        String ext = extOf(originalName);
        if (BLOCKED_EXT.contains(ext)) {
            throw new BizException(1002, "出于安全考虑，不支持上传 ." + ext + " 文件");
        }
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = guessContentType(ext);
        }

        String key = LocalDate.now().format(DAY_PATH) + "/" + UUID.randomUUID().toString().replace("-", "")
                + (ext.isEmpty() ? "" : "." + ext);
        FileStorage storage = storageService.current();
        storage.put(key, data, contentType);

        String normalizedBizType = normalizeBizType(bizType);
        String fileNo = codeGenerator.next("F");
        Long id = jdbcTemplate.queryForObject("""
                insert into attach_file (file_no, original_name, ext, content_type, size_bytes, sha256,
                                         storage_type, storage_key, bucket, biz_type, biz_id, biz_no, uploaded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, fileNo, originalName, ext.isEmpty() ? null : ext, contentType,
                (long) data.length, Hashes.sha256Hex(data), storage.type(), key, storage.bucket(),
                normalizedBizType, bizId, blankToNull(bizNo), CurrentUser.name());

        String logBizNo = blankToNull(bizNo) == null ? fileNo : bizNo.trim();
        operLogService.touch("FILE", id, logBizNo, "上传附件「" + originalName + "」"
                + (normalizedBizType == null ? "" : "（" + normalizedBizType
                + (bizNo == null || bizNo.isBlank() ? "" : " · " + bizNo) + "）"));
        operLogService.action("上传");
        return detail(id);
    }

    public List<Map<String, Object>> list(String bizType, Long bizId, String keyword, String storageType,
                                          String from, String to, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                select id, file_no as "fileNo", original_name as "originalName", ext,
                       content_type as "contentType", size_bytes as "sizeBytes", sha256,
                       storage_type as "storageType", storage_key as "storageKey", bucket,
                       biz_type as "bizType", biz_id as "bizId", biz_no as "bizNo",
                       uploaded_by as "uploadedBy",
                       to_char(uploaded_at, 'YYYY-MM-DD HH24:MI') as "uploadedAt"
                from attach_file
                where deleted = false
                """);
        List<Object> params = new ArrayList<>();
        if (bizType != null && !bizType.isBlank()) {
            sql.append(" and biz_type = ?");
            params.add(bizType.trim().toUpperCase(Locale.ROOT));
        }
        if (bizId != null) {
            sql.append(" and biz_id = ?");
            params.add(bizId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (original_name ilike ? or file_no ilike ? or biz_no ilike ? or uploaded_by ilike ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (storageType != null && !storageType.isBlank()) {
            sql.append(" and storage_type = ?");
            params.add(storageType.trim().toUpperCase(Locale.ROOT));
        }
        if (from != null && !from.isBlank()) {
            sql.append(" and uploaded_at >= cast(? as date)");
            params.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            sql.append(" and uploaded_at < cast(? as date) + 1");
            params.add(to.trim());
        }
        int size = limit == null || limit <= 0 ? 200 : Math.min(limit, 1000);
        sql.append(" order by id desc limit ").append(size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        rows.forEach(this::decorate);
        return rows;
    }

    public Map<String, Object> detail(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, file_no as "fileNo", original_name as "originalName", ext,
                       content_type as "contentType", size_bytes as "sizeBytes", sha256,
                       storage_type as "storageType", storage_key as "storageKey", bucket,
                       biz_type as "bizType", biz_id as "bizId", biz_no as "bizNo",
                       uploaded_by as "uploadedBy",
                       to_char(uploaded_at, 'YYYY-MM-DD HH24:MI') as "uploadedAt"
                from attach_file where id = ? and deleted = false
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4040, "附件不存在");
        }
        decorate(rows.get(0));
        return rows.get(0);
    }

    public Download download(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select original_name, content_type, storage_type, storage_key, deleted, file_no, biz_no
                from attach_file where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4040, "附件不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("deleted"))) {
            throw new BizException(4040, "附件已删除");
        }
        String storageType = String.valueOf(row.get("storage_type"));
        byte[] data = storageService.forType(storageType).get(String.valueOf(row.get("storage_key")));
        String name = String.valueOf(row.get("original_name"));
        String contentType = row.get("content_type") == null
                ? "application/octet-stream" : String.valueOf(row.get("content_type"));
        operLogService.touch("FILE", id, logNoOf(row), "下载附件「" + name + "」");
        operLogService.action("下载");
        return new Download(name, contentType, data);
    }

    @Transactional
    public void delete(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select file_no, original_name, storage_type, storage_key, deleted, biz_no
                from attach_file where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4040, "附件不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("deleted"))) {
            return;
        }
        jdbcTemplate.update("update attach_file set deleted = true where id = ?", id);
        try {
            storageService.forType(String.valueOf(row.get("storage_type")))
                    .delete(String.valueOf(row.get("storage_key")));
        } catch (Exception e) {
            // 台账已标记删除，本体清不掉只记日志，避免影响业务操作
            log.warn("附件本体删除失败 id={} key={}", id, row.get("storage_key"), e);
        }
        operLogService.touch("FILE", id, logNoOf(row),
                "删除附件「" + row.get("original_name") + "」");
        operLogService.action("删除");
    }

    public Map<String, Object> storageStatus() {
        return storageService.status();
    }

    public Map<String, Object> testStorage() {
        String provider = storageService.provider();
        FileStorage storage = storageService.current();
        storage.probe();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("ok", true);
        result.put("message", "S3".equals(provider)
                ? "S3 连接正常，桶 " + storage.bucket() + " 可读写"
                : "本地目录可写：" + storageService.status().get("localRoot"));
        return result;
    }

    private void decorate(Map<String, Object> row) {
        Object id = row.get("id");
        row.put("url", "/api/files/" + id + "/download");
        Object size = row.get("sizeBytes");
        row.put("sizeText", size == null ? "-" : sizeText(((Number) size).longValue()));
    }

    /** 日志里优先展示业务单号，没有业务单号才退回台账号 */
    private static String logNoOf(Map<String, Object> row) {
        Object bizNo = row.get("biz_no");
        if (bizNo == null || String.valueOf(bizNo).isBlank()) {
            return String.valueOf(row.get("file_no"));
        }
        return String.valueOf(bizNo);
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\r\\n\\t]", "").trim();
        if (name.isEmpty()) {
            name = "未命名文件";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,12}") ? ext : "";
    }

    private static String guessContentType(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "txt", "log", "md" -> "text/plain";
            case "csv" -> "text/csv";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "zip" -> "application/zip";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    private static String normalizeBizType(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return null;
        }
        String value = bizType.trim().toUpperCase(Locale.ROOT);
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
