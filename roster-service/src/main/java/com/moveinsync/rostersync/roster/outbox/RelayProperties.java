package com.moveinsync.rostersync.roster.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.relay.* (spec §14). */
@ConfigurationProperties("app.relay")
public record RelayProperties(boolean enabled, long pollMs, int batch, int leaseSecs, int maxAttempts, int retentionDays) {
}
