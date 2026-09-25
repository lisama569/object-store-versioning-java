package com.github.lisama569.objectstoreversioningjava.web;

import com.github.lisama569.objectstoreversioningjava.core.ChecksumMismatchException;
import com.github.lisama569.objectstoreversioningjava.core.InvalidKeyException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectDeletedException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectNotFoundException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectStoreException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.VersionNotFoundException;
import com.github.lisama569.objectstoreversioningjava.service.ObjectStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 简单 HTTP 入口，便于手工验证版本化语义。
 *
 * <ul>
 *   <li>PUT/POST {@code /objects/{key}}：写入（body 即内容字节）；</li>
 *   <li>GET {@code /objects/{key}}：读最新版；{@code ?version=N} 读指定版本；
 *       {@code ?versions=true} 列举版本链（键可含斜杠）；</li>
 *   <li>DELETE {@code /objects/{key}}：逻辑删除（追加删除标记）；
 *       {@code ?purgeVersion=N} 永久删除单个版本；</li>
 *   <li>空间回收入口见 {@link GcController}：POST {@code /internal/gc}。</li>
 * </ul>
 *
 * 不同失败原因映射为不同状态码：400 非法键 / 404 缺失（对象或版本）/
 * 410 已删除 / 422 校验失败。
 */
@RestController
@RequestMapping("/objects")
public class ObjectController {

    private final ObjectStore store;

    public ObjectController(ObjectStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * {@code {*key}} 捕获的剩余路径带前导 '/'，去掉它得到真实对象键；
     * 键的所有合法性（空、空白、控制字符、超长）仍由 {@code ObjectKey} 在门面边界统一校验。
     */
    private static String key(String raw) {
        if (raw != null && raw.startsWith("/")) {
            return raw.substring(1);
        }
        return raw;
    }

    /** 写入：body 即内容字节；不限制 Content-Type，缺省按空内容处理。 */
    @PostMapping("/{*key}")
    public ResponseEntity<String> put(@PathVariable String key, @RequestBody(required = false) byte[] body) {
        ObjectVersion v = store.put(key(key), body == null ? new byte[0] : body);
        return ResponseEntity.ok("已写入 " + v.describe());
    }

    @GetMapping("/{*key}")
    public ResponseEntity<?> get(@PathVariable String key,
                                 @RequestParam(required = false) Long version,
                                 @RequestParam(required = false, defaultValue = "false") boolean versions) {
        String k = key(key);
        // ?versions=true 列举版本链；?version=N 读指定版本；否则读最新版。
        if (versions) {
            List<String> chain = store.listVersions(k).stream().map(ObjectVersion::describe).toList();
            return ResponseEntity.ok(chain);
        }
        var content = version == null ? store.get(k) : store.getVersion(k, version);
        return ResponseEntity.ok()
                .header("X-Object-Version", String.valueOf(content.version().value()))
                .header("X-Content-Id", content.contentId().sha256())
                .body(content.data());
    }

    @DeleteMapping("/{*key}")
    public ResponseEntity<String> delete(@PathVariable String key,
                                         @RequestParam(required = false) Long purgeVersion) {
        String k = key(key);
        if (purgeVersion != null) {
            store.purgeVersion(k, purgeVersion);
            return ResponseEntity.ok("已永久删除版本 v" + purgeVersion);
        }
        ObjectVersion v = store.delete(k);
        return ResponseEntity.ok("已逻辑删除，追加 " + v.describe());
    }

    @ExceptionHandler(InvalidKeyException.class)
    public ResponseEntity<String> invalidKey(ObjectStoreException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("[INVALID_KEY] " + e.getMessage());
    }

    @ExceptionHandler({ObjectNotFoundException.class, VersionNotFoundException.class})
    public ResponseEntity<String> notFound(ObjectStoreException e) {
        String tag = e instanceof VersionNotFoundException ? "[VERSION_NOT_FOUND]" : "[OBJECT_NOT_FOUND]";
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(tag + " " + e.getMessage());
    }

    @ExceptionHandler(ObjectDeletedException.class)
    public ResponseEntity<String> deleted(ObjectStoreException e) {
        return ResponseEntity.status(HttpStatus.GONE).body("[OBJECT_DELETED] " + e.getMessage());
    }

    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<String> corrupt(ObjectStoreException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("[CHECKSUM_MISMATCH] " + e.getMessage());
    }
}
