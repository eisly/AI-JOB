package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 头像私有存储：{storageDir}/{userId}/avatar/{version}，写临时文件后原子移动。
 * 读取前做路径归一化校验，防止越界访问其他账号目录。
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);

    private final Path root;

    public AvatarStorageService(@Value("${app.files.storage-dir:./data/private-files}") String storageDir) {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    public String store(String userId, int version, byte[] bytes) {
        Path directory = root.resolve(userId).resolve("avatar").normalize();
        Path target = directory.resolve(String.valueOf(version)).normalize();
        Path temp = directory.resolve(".tmp-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(directory);
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return storageKey(userId, version);
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw new BusinessException(com.aijob.backend.common.ApiErrorCode.DEPENDENCY_UNAVAILABLE,
                    "头像存储暂不可用");
        }
    }

    public byte[] read(String userId, String storageKey) {
        Path target = safeResolve(userId, storageKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw AccountExceptionFactory.avatarNotFound();
        }
    }

    public void delete(String userId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(safeResolve(userId, storageKey));
        } catch (Exception ex) {
            log.warn("avatar file delete failed userId={} key={} reason={}", userId, storageKey, ex.getMessage());
        }
    }

    public boolean isWritable() {
        try {
            Files.createDirectories(root);
            return Files.isWritable(root);
        } catch (Exception ex) {
            return false;
        }
    }

    public Path root() {
        return root;
    }

    private Path safeResolve(String userId, String storageKey) {
        Path base = root.resolve(userId).resolve("avatar").normalize();
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(base)) {
            throw BusinessException.forbidden();
        }
        return target;
    }

    private static String storageKey(String userId, int version) {
        return userId + "/avatar/" + version;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort 清理
        }
    }
}