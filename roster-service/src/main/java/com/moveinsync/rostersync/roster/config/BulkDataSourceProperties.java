package com.moveinsync.rostersync.roster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Second connection pool for reconciliation (spec §14): socketTimeout above the 300 s statement_timeout. */
@ConfigurationProperties("app.bulk-datasource")
public record BulkDataSourceProperties(String url, String username, String password,
                                       int maximumPoolSize, long connectionTimeout) {
}
