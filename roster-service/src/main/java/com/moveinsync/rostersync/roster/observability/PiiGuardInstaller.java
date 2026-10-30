package com.moveinsync.rostersync.roster.observability;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Installs {@link PiiGuardFilter} once the logging system is configured. */
@Component
public class PiiGuardInstaller implements ApplicationListener<ApplicationStartedEvent> {

    public PiiGuardInstaller() {
        PiiGuardFilter.install();
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        PiiGuardFilter.install();
    }
}
