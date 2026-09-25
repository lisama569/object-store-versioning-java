package com.github.lisama569.objectstoreversioningjava.web;

import com.github.lisama569.objectstoreversioningjava.core.GcReport;
import com.github.lisama569.objectstoreversioningjava.service.GarbageCollector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 空间回收触发入口（独立路径，避免与对象键路由冲突）。 */
@RestController
@RequestMapping("/internal")
public class GcController {

    private final GarbageCollector gc;

    public GcController(GarbageCollector gc) {
        this.gc = Objects.requireNonNull(gc);
    }

    @PostMapping("/gc")
    public ResponseEntity<String> gc() {
        GcReport r = gc.collect();
        return ResponseEntity.ok(String.format(
                "GC 完成：扫描块=%d，删除=%d，保留=%d；删除=%s；保留=%s",
                r.scanned(), r.deleted(), r.retainedContentIds().size(),
                r.deletedContentIds(), r.retainedContentIds()));
    }
}
