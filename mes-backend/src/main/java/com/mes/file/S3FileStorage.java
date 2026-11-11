package com.mes.file;

import com.mes.common.BizException;
import com.mes.platform.config.ConfigService;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * S3 兼容存储：使用 Signature V4 直接走 HTTP，不依赖第三方 SDK。
 * 地址、区域、桶、密钥、路径风格全部来自系统配置，改完立即生效。
 */
@Component
public class S3FileStorage implements FileStorage {

    private static final String EMPTY_SHA256 = Hashes.sha256Hex(new byte[0]);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ConfigService configService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public S3FileStorage(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String type() {
        return "S3";
    }

    @Override
    public String bucket() {
        return configService.stringValue("storage.s3.bucket", "");
    }

    private record S3Config(String endpoint, String region, String bucket,
                            String accessKey, String secretKey, boolean pathStyle) {
    }

    private S3Config cfg() {
        String endpoint = trimSlash(configService.stringValue("storage.s3.endpoint", ""));
        String region = configService.stringValue("storage.s3.region", "us-east-1");
        String bucket = configService.stringValue("storage.s3.bucket", "");
        String accessKey = configService.stringValue("storage.s3.access-key", "");
        String secretKey = configService.stringValue("storage.s3.secret-key", "");
        boolean pathStyle = configService.booleanValue("storage.s3.path-style", true);

        if (endpoint.isBlank()) {
            throw new BizException(5011, "S3 未配置：请先在系统配置里填写 storage.s3.endpoint");
        }
        if (bucket.isBlank()) {
            throw new BizException(5011, "S3 未配置：请先在系统配置里填写 storage.s3.bucket");
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new BizException(5011, "S3 未配置：请先在系统配置里填写 Access Key / Secret Key");
        }
        return new S3Config(endpoint, region, bucket, accessKey, secretKey, pathStyle);
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        S3Config cfg = cfg();
        String type = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        HttpResponse<byte[]> response = send(cfg, "PUT", key, data, type);
        ensureOk(response, "写入", cfg, key);
    }

    @Override
    public byte[] get(String key) {
        S3Config cfg = cfg();
        HttpResponse<byte[]> response = send(cfg, "GET", key, null, null);
        if (response.statusCode() == 404) {
            throw new BizException(4040, "附件文件不存在，可能已被清理");
        }
        ensureOk(response, "读取", cfg, key);
        return response.body();
    }

    @Override
    public void delete(String key) {
        S3Config cfg = cfg();
        HttpResponse<byte[]> response = send(cfg, "DELETE", key, null, null);
        if (response.statusCode() == 404) {
            return;
        }
        ensureOk(response, "删除", cfg, key);
    }

    @Override
    public void probe() {
        String key = "_probe/" + UUID.randomUUID() + ".txt";
        put(key, "mes storage probe".getBytes(StandardCharsets.UTF_8), "text/plain");
        delete(key);
    }

    private HttpResponse<byte[]> send(S3Config cfg, String method, String key,
                                      byte[] payload, String contentType) {
        URI uri = objectUri(cfg, key);
        String payloadHash = payload == null ? EMPTY_SHA256 : Hashes.sha256Hex(payload);
        String amzDate = AMZ_DATE.format(Instant.now());

        Map<String, String> canonical = new TreeMap<>();
        canonical.put("host", uri.getAuthority());
        canonical.put("x-amz-content-sha256", payloadHash);
        canonical.put("x-amz-date", amzDate);
        if (contentType != null && !contentType.isBlank()) {
            canonical.put("content-type", contentType);
        }
        String authorization = S3Signer.authorization(method, uri.getRawPath(), "", canonical,
                payloadHash, amzDate, cfg.region(), cfg.accessKey(), cfg.secretKey());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization);
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofByteArray(payload == null ? new byte[0] : payload));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new BizException(5012, "S3 请求失败（" + cfg.endpoint() + "）：" + e.getMessage());
        }
    }

    private void ensureOk(HttpResponse<byte[]> response, String action, S3Config cfg, String key) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String body = response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
        if (body.length() > 180) {
            body = body.substring(0, 180) + "...";
        }
        throw new BizException(5012, "S3 " + action + "失败（HTTP " + status + "，桶 " + cfg.bucket()
                + "，key " + key + "）：" + body);
    }

    private URI objectUri(S3Config cfg, String key) {
        URI base = URI.create(cfg.endpoint());
        String encodedKey = S3Signer.encodeKey(key);
        String authority = base.getRawAuthority();
        String path;
        if (cfg.pathStyle()) {
            path = "/" + S3Signer.encodeSegment(cfg.bucket()) + "/" + encodedKey;
        } else {
            path = "/" + encodedKey;
            String host = cfg.bucket() + "." + base.getHost();
            authority = base.getPort() > 0 ? host + ":" + base.getPort() : host;
        }
        return URI.create(base.getScheme() + "://" + authority + path);
    }

    private static String trimSlash(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
