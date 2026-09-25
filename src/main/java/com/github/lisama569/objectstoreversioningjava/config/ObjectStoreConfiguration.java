package com.github.lisama569.objectstoreversioningjava.config;

import com.github.lisama569.objectstoreversioningjava.store.ObjectStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 装配：把 ObjectStore 注册为单例 Bean。
 */
@Configuration
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreConfiguration {

    @Bean
    public ObjectStore objectStore(ObjectStoreProperties properties) {
        return new ObjectStore(properties);
    }
}
