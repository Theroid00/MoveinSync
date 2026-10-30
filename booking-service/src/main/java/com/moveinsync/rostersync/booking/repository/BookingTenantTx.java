package com.moveinsync.rostersync.booking.repository;

import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction scoped to one tenant: the first statement sets {@code app.tenant_id}
 * transaction-locally, which the RLS policies read.
 */
@Component
public class BookingTenantTx {

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public BookingTenantTx(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public <T> T inTenant(String tenantId, TransactionCallback<T> callback) {
        Objects.requireNonNull(tenantId, "tenantId");
        return transactions.execute(status -> {
            jdbc.sql("SELECT set_config('app.tenant_id', :tenantId, true)")
                    .param("tenantId", tenantId)
                    .query(String.class)
                    .single();
            return callback.doInTransaction(status);
        });
    }
}
