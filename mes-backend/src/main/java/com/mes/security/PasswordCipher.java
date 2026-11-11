package com.mes.security;

import com.mes.common.BizException;
import org.springframework.stereotype.Service;

/**
 * 密码入参解析：带密文时优先解密，没有密文时按明文处理。
 * 保留明文通道是为了兼容移动端、接口脚本与尚未升级的客户端，
 * 浏览器端一律走 RSA-OAEP 密文。
 */
@Service
public class PasswordCipher {

    private final RsaCryptoService rsaCryptoService;

    public PasswordCipher(RsaCryptoService rsaCryptoService) {
        this.rsaCryptoService = rsaCryptoService;
    }

    public String resolve(String plain, String encrypted, String keyId) {
        if (encrypted != null && !encrypted.isBlank()) {
            return rsaCryptoService.decrypt(encrypted, keyId);
        }
        if (plain == null || plain.isBlank()) {
            throw new BizException(1002, "密码不能为空");
        }
        return plain;
    }
}
