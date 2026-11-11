package com.mes.file;

import com.mes.platform.config.ConfigService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 存储路由：每次按系统配置 storage.provider 选实现，改配置立即生效；
 * 读取老文件时用记录里的 storage_type，不跟当前配置走。
 */
@Service
public class StorageService {

    private final ConfigService configService;
    private final LocalFileStorage localFileStorage;
    private final S3FileStorage s3FileStorage;

    public StorageService(ConfigService configService, LocalFileStorage localFileStorage,
                          S3FileStorage s3FileStorage) {
        this.configService = configService;
        this.localFileStorage = localFileStorage;
        this.s3FileStorage = s3FileStorage;
    }

    public String provider() {
        String value = configService.stringValue("storage.provider", "LOCAL");
        return "S3".equalsIgnoreCase(value) ? "S3" : "LOCAL";
    }

    public FileStorage current() {
        return forType(provider());
    }

    public FileStorage forType(String type) {
        return "S3".equalsIgnoreCase(type) ? s3FileStorage : localFileStorage;
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        String provider = provider();
        map.put("provider", provider);
        map.put("localRoot", localFileStorage.root().toString());
        map.put("localWritable", localFileStorage.writable());

        String endpoint = configService.stringValue("storage.s3.endpoint", "");
        String bucket = configService.stringValue("storage.s3.bucket", "");
        String accessKey = configService.stringValue("storage.s3.access-key", "");
        String secretKey = configService.stringValue("storage.s3.secret-key", "");
        map.put("s3Endpoint", endpoint);
        map.put("s3Bucket", bucket);
        map.put("s3Region", configService.stringValue("storage.s3.region", "us-east-1"));
        map.put("s3PathStyle", configService.booleanValue("storage.s3.path-style", true));
        map.put("s3Configured", !endpoint.isBlank() && !bucket.isBlank()
                && !accessKey.isBlank() && !secretKey.isBlank());
        map.put("maxSizeMb", 20);
        map.put("description", "S3".equals(provider.toUpperCase(Locale.ROOT))
                ? "新上传写入 S3 对象存储，历史本地文件仍可正常下载"
                : "新上传写入本机目录，切换为 S3 后老文件仍可正常下载");
        return map;
    }
}
