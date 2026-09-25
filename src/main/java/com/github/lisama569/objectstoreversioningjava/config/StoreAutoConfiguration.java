package com.github.lisama569.objectstoreversioningjava.config;

import com.github.lisama569.objectstoreversioningjava.service.DefaultGarbageCollector;
import com.github.lisama569.objectstoreversioningjava.service.DefaultObjectStore;
import com.github.lisama569.objectstoreversioningjava.service.GarbageCollector;
import com.github.lisama569.objectstoreversioningjava.service.ObjectStore;
import com.github.lisama569.objectstoreversioningjava.service.StoreLocks;
import com.github.lisama569.objectstoreversioningjava.storage.BlobStorage;
import com.github.lisama569.objectstoreversioningjava.storage.InMemoryMetadataStore;
import com.github.lisama569.objectstoreversioningjava.storage.LocalBlobStorage;
import com.github.lisama569.objectstoreversioningjava.storage.MetadataStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 装配本地块存储、进程内元数据、共享锁、对象存储门面与回收器。
 * StoreLocks 为单例，保证 ObjectStore 与 GarbageCollector 使用同一把全局读写锁。
 */
@Configuration
@EnableConfigurationProperties(StoreProperties.class)
public class StoreAutoConfiguration {

    @Bean
    public StoreLocks storeLocks() {
        return new StoreLocks();
    }

    @Bean
    public BlobStorage blobStorage(StoreProperties props) {
        return new LocalBlobStorage(Path.of(props.getDataDir(), "blobs-root").normalize());
    }

    @Bean
    public MetadataStore metadataStore() {
        return new InMemoryMetadataStore();
    }

    @Bean
    public ObjectStore objectStore(BlobStorage blobStorage, MetadataStore metadataStore,
                                   StoreLocks locks, StoreProperties props) {
        return new DefaultObjectStore(blobStorage, metadataStore, locks, props.getMaxKeyLength());
    }

    @Bean
    public GarbageCollector garbageCollector(BlobStorage blobStorage, MetadataStore metadataStore, StoreLocks locks) {
        return new DefaultGarbageCollector(blobStorage, metadataStore, locks);
    }
}
