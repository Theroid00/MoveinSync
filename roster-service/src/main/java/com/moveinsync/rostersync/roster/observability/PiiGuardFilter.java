package com.moveinsync.rostersync.roster.observability;

import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.moveinsync.rostersync.roster.api.EmployeeView;
import com.moveinsync.rostersync.roster.api.TransportPatchRequest;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.repository.EmployeeRow;
import com.moveinsync.rostersync.roster.repository.StagingRepository;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Defence in depth (spec §13.3): drops any log event whose arguments include a request or entity type
 * that carries personal data. Log statements are written with ids only; this catches the accident of
 * passing a whole object.
 */
public class PiiGuardFilter extends TurboFilter {

    static final Set<Class<?>> PII_TYPES = Set.of(HrEventRequest.class, HrRecord.class, EmployeeRow.class,
            EmployeeView.class, TransportPatchRequest.class, StagingRepository.StagedRow.class);

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (params != null) {
            for (Object param : params) {
                if (param != null && PII_TYPES.contains(param.getClass())) {
                    return FilterReply.DENY;
                }
            }
        }
        return FilterReply.NEUTRAL;
    }

    /** Idempotent: the logging system may be re-initialised (e.g. per test context). */
    public static void install() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context
                && context.getTurboFilterList().stream().noneMatch(PiiGuardFilter.class::isInstance)) {
            PiiGuardFilter filter = new PiiGuardFilter();
            filter.setName("pii-guard");
            filter.start();
            context.addTurboFilter(filter);
        }
    }
}
