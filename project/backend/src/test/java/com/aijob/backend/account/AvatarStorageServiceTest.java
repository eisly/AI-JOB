package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesReadsAndDeletes() {
        AvatarStorageService storage = new AvatarStorageService(tempDir.toString());
        byte[] content = "avatar-bytes".getBytes(StandardCharsets.UTF_8);

        String key = storage.store("u1", 1, content);

        assertThat(key).isEqualTo("u1/avatar/1");
        assertThat(storage.read("u1", key)).isEqualTo(content);
        storage.delete("u1", key);
        assertThatThrownBy(() -> storage.read("u1", key)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPathTraversal() {
        AvatarStorageService storage = new AvatarStorageService(tempDir.toString());
        assertThatThrownBy(() -> storage.read("u1", "u2/avatar/1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> storage.read("u1", "../u1/avatar/1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createsRootAndReportsWritable() {
        Path root = tempDir.resolve("private-files");
        AvatarStorageService storage = new AvatarStorageService(root.toString());
        assertThat(storage.isWritable()).isTrue();
        assertThat(storage.root()).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    void overwritesSameVersionWithoutLeftoverTempFiles() throws Exception {
        AvatarStorageService storage = new AvatarStorageService(tempDir.toString());
        storage.store("u1", 2, new byte[]{1, 2, 3});
        storage.store("u1", 2, new byte[]{4, 5, 6});

        try (var files = java.nio.file.Files.list(tempDir.resolve("u1/avatar"))) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith(".tmp-")))
                    .isEmpty();
        }
    }
}