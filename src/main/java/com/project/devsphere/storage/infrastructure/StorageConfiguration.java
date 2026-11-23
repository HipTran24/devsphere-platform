package com.project.devsphere.storage.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code devsphere.storage.s3.*} for whichever storage adapter is active. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties.class)
public class StorageConfiguration {
}
