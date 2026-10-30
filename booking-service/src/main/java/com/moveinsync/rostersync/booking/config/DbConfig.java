package com.moveinsync.rostersync.booking.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

/** JdbcClient with the vendor error-code translator (unique violations, lock timeouts classified). */
@Configuration(proxyBeanMethods = false)
public class DbConfig {

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator(dataSource));
        return JdbcClient.create(template);
    }
}
