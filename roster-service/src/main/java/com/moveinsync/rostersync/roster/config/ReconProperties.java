package com.moveinsync.rostersync.roster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.recon.*: where uploaded snapshots are stored, and whether interrupted runs are recovered at startup. */
@ConfigurationProperties("app.recon")
public record ReconProperties(String dir, Boolean recoverOnStartup) {

    public boolean recover() {
        return recoverOnStartup == null || recoverOnStartup;
    }
}
