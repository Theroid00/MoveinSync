package com.moveinsync.rostersync.roster.config;

import javax.sql.DataSource;

import com.moveinsync.rostersync.roster.repository.TenantTx;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two pools on the same app role (so RLS and advisory locks behave identically):
 * <ul>
 *   <li>primary: API, relay, seeders. socketTimeout 10 s keeps a paused DB a fast 503;</li>
 *   <li>bulk ({@code @Qualifier("bulk")}): reconciliation staging and apply. socketTimeout 330 s and
 *       statement_timeout 300 s per connection, so a legitimately long apply is not killed.</li>
 * </ul>
 * Bulk beans are not default candidates: they are injected only where {@code @Qualifier("bulk")} asks.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BulkDataSourceProperties.class)
public class BulkDbConfig {

    public static final String BULK = "bulk";

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    @Primary
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @Primary
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    TenantTx tenantTx(TransactionTemplate transactionTemplate, JdbcClient jdbcClient) {
        return new TenantTx(transactionTemplate, jdbcClient);
    }

    @Bean(defaultCandidate = false)
    @Qualifier(BULK)
    HikariDataSource bulkDataSource(BulkDataSourceProperties properties) {
        HikariDataSource ds = new HikariDataSource();   // lazy: connects on first use
        ds.setPoolName("bulk");
        ds.setJdbcUrl(properties.url());
        ds.setUsername(properties.username());
        ds.setPassword(properties.password());
        ds.setMaximumPoolSize(properties.maximumPoolSize());
        ds.setConnectionTimeout(properties.connectionTimeout());
        // The role default is 5 s (init script); bulk work needs the 300 s ceiling on every connection.
        ds.setConnectionInitSql("SET statement_timeout = '300s'");
        return ds;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(BULK)
    PlatformTransactionManager bulkTxManager(@Qualifier(BULK) DataSource bulkDataSource) {
        return new JdbcTransactionManager(bulkDataSource);
    }

    @Bean(defaultCandidate = false)
    @Qualifier(BULK)
    TransactionTemplate bulkTxTemplate(@Qualifier(BULK) PlatformTransactionManager bulkTxManager) {
        return new TransactionTemplate(bulkTxManager);
    }

    @Bean(defaultCandidate = false)
    @Qualifier(BULK)
    JdbcClient bulkJdbcClient(@Qualifier(BULK) DataSource bulkDataSource) {
        return JdbcClient.create(bulkDataSource);
    }

    @Bean(defaultCandidate = false)
    @Qualifier(BULK)
    TenantTx bulkTenantTx(@Qualifier(BULK) TransactionTemplate bulkTxTemplate,
                          @Qualifier(BULK) JdbcClient bulkJdbcClient) {
        return new TenantTx(bulkTxTemplate, bulkJdbcClient);
    }
}
