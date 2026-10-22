package com.yt.agent.platform.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;

/**
 * 会话存储装配：按配置选择 JDBC 或内存实现。
 *
 * <p>降级策略：持久化初始化失败（驱动缺失、目录不可写、库连不上）时<b>回退内存并告警</b>，
 * 而不是让应用启动失败。理由：Agent 的核心价值是"能回答"，历史丢失是体验降级而非功能不可用；
 * 但启动失败会让整个服务不可用。同时日志必须足够醒目，避免问题被长期忽略。</p>
 *
 * <p>这里自建 DataSource 而不复用主应用：主应用排除了 DataSourceAutoConfiguration，
 * 且问数（nl2sql）已有独立只读数据源。会话库与业务库在权限、生命周期上都应隔离——
 * 会话库需要写权限，业务库必须只读，混用会破坏"只读账号"这一安全边界。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfig {

    @Bean
    public SessionStore sessionStore(PersistenceProperties properties) {
        if (!properties.isEnabled() || properties.getUrl() == null || properties.getUrl().isBlank()) {
            log.warn("[Persistence] 未启用持久化，会话保存在内存中（重启丢失）。生产环境请配置 agent.persistence.url");
            return new InMemorySessionStore(properties.getMaxTurnsPerSession(), properties.getMaxSessions());
        }
        try {
            prepareDirectory(properties.getUrl());
            DataSource dataSource = buildDataSource(properties);
            String backend = properties.getUrl().startsWith("jdbc:postgresql") ? "jdbc:postgresql" : "jdbc:h2";
            return new JdbcSessionStore(dataSource, properties.getTablePrefix(),
                    properties.getMaxTurnsPerSession(), backend);
        } catch (Exception e) {
            log.error("[Persistence] 初始化失败，已降级为内存存储（历史将不持久）: {}", e.getMessage(), e);
            return new InMemorySessionStore(properties.getMaxTurnsPerSession(), properties.getMaxSessions());
        }
    }

    private DataSource buildDataSource(PersistenceProperties properties) throws ClassNotFoundException {
        @SuppressWarnings("unchecked")
        Class<? extends Driver> driverClass = (Class<? extends Driver>) Class.forName(properties.getDriverClassName());
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(driverClass);
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        log.info("[Persistence] 会话存储: {} (driver={})", properties.getUrl(), properties.getDriverClassName());
        return dataSource;
    }

    /**
     * H2 文件库不会自动创建父目录，这里提前建好，避免首次启动因目录缺失而降级。
     */
    private void prepareDirectory(String url) {
        if (!url.startsWith("jdbc:h2:file:")) {
            return;
        }
        try {
            String path = url.substring("jdbc:h2:file:".length());
            int semi = path.indexOf(';');
            if (semi > 0) {
                path = path.substring(0, semi);
            }
            Path parent = Path.of(path).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            log.warn("[Persistence] 创建数据目录失败（将由 H2 自行处理）: {}", e.getMessage());
        }
    }
}
