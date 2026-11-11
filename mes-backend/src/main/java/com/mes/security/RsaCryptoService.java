package com.mes.security;

import com.mes.common.BizException;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 登录密码的传输加密：服务端持有一对 RSA 密钥，前端用公钥加密，服务端用私钥解密。
 * 只解决「密码不在网络上裸奔」，落库仍然是 BCrypt 单向哈希，两者不能互相替代。
 */
@Service
public class RsaCryptoService {

    private static final int KEY_SIZE = 2048;
    private static final String ALGORITHM = "RSA";
    /** 与浏览器 Web Crypto 的 RSA-OAEP + SHA-256 对齐，MGF1 也必须用 SHA-256 */
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final KeyPair keyPair;
    private final String publicKeyPem;
    private final String keyId;

    public RsaCryptoService() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(KEY_SIZE);
            this.keyPair = generator.generateKeyPair();
            this.publicKeyPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());
            this.keyId = fingerprint(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("初始化登录加密密钥失败", e);
        }
    }

    public String publicKeyPem() {
        return publicKeyPem;
    }

    public String keyId() {
        return keyId;
    }

    /**
     * 解密前端提交的 Base64 密文。keyId 不一致说明页面拿的是上一轮公钥（服务重启过），
     * 直接提示刷新，避免用户看到「密码错误」这种误导信息。
     */
    public String decrypt(String encryptedBase64, String clientKeyId) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            throw new BizException(1002, "密码不能为空");
        }
        if (clientKeyId != null && !clientKeyId.isBlank() && !keyId.equals(clientKeyId)) {
            throw new BizException(4009, "登录密钥已更新，请刷新页面后重试");
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), OAEP_SHA256);
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encryptedBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BizException(1002, "密码密文格式不正确");
        } catch (Exception e) {
            throw new BizException(1002, "密码解密失败，请刷新页面后重试");
        }
    }

    private String toPem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    private String fingerprint(byte[] encoded) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return "default";
        }
    }
}
