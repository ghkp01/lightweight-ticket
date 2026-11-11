package com.mes.file;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature V4 签名，只用 JDK 自带能力，不引第三方 SDK。
 * 支持 S3 与兼容 S3 的对象存储（MinIO、云厂商对象存储）。
 */
final class S3Signer {

    private S3Signer() {
    }

    /**
     * @param method      PUT / GET / DELETE
     * @param rawPath     已经做过百分号编码的路径（含 /bucket/key）
     * @param query       规范查询串，没有就传空串
     * @param headers     参与签名的请求头，至少包含 host、x-amz-content-sha256、x-amz-date
     * @param payloadHash 请求体的 sha256 十六进制
     */
    static String authorization(String method, String rawPath, String query,
                                Map<String, String> headers, String payloadHash,
                                String amzDate, String region, String accessKey, String secretKey) {
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";

        TreeMap<String, String> sorted = new TreeMap<>();
        headers.forEach((name, value) ->
                sorted.put(name.toLowerCase(Locale.ROOT), value == null ? "" : value.trim()));
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            canonicalHeaders.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
            if (signedBuilder.length() > 0) {
                signedBuilder.append(';');
            }
            signedBuilder.append(entry.getKey());
        }
        String signedHeaders = signedBuilder.toString();
        String canonicalRequest = method + "\n"
                + (rawPath == null || rawPath.isEmpty() ? "/" : rawPath) + "\n"
                + (query == null ? "" : query) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + Hashes.sha256Hex(canonicalRequest);

        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = Hashes.toHex(hmac(kSigning, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** 对象 key 按段做 RFC3986 编码，保留 / 作为分隔 */
    static String encodeKey(String key) {
        String[] segments = key.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encodeSegment(segments[i]));
        }
        return out.toString();
    }

    static String encodeSegment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("S3 签名失败：" + e.getMessage(), e);
        }
    }
}
