package com.project.devsphere.gitintegration.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers GitHub App configuration properties.
 */
@Configuration
@EnableConfigurationProperties(GitHubAppProperties.class)
public class GitHubAppConfiguration {
}
