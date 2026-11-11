package com.mes.file;

import com.mes.common.BizException;
import com.mes.platform.config.ConfigService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 本地磁盘存储：根目录来自系统配置 storage.local.root，相对路径以服务启动目录为基准。
 * 所有访问都限制在根目录内，避免 key 被拼出 ../ 穿越。
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final ConfigService configService;

    public LocalFileStorage(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String type() {
        return "LOCAL";
    }

    @Override
    public String bucket() {
        return null;
    }

    public Path root() {
        String configured = configService.stringValue("storage.local.root", "data/attachments");
        Path path = Paths.get(configured);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new BizException(5010, "本地存储写入失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw new BizException(4040, "附件文件不存在，可能已被清理");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BizException(5010, "本地存储读取失败：" + e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new BizException(5010, "本地存储删除失败：" + e.getMessage());
        }
    }

    @Override
    public void probe() {
        Path root = root();
        Path probe = root.resolve(".probe-" + UUID.randomUUID() + ".txt");
        try {
            Files.createDirectories(root);
            Files.writeString(probe, "mes storage probe", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new BizException(5011, "本地目录不可写：" + root + "（" + e.getMessage() + "）");
        }
    }

    public boolean writable() {
        try {
            Path root = root();
            Files.createDirectories(root);
            return Files.isWritable(root);
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new BizException(1002, "存储 key 不能为空");
        }
        Path root = root();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(1002, "非法的存储路径");
        }
        return target;
    }
}
