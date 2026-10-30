package com.moveinsync.rostersync.roster;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Full application against postgres:18 with MockMvc and a fresh tenant per test. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractApiIT extends AbstractPostgresIT {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    protected RosterApi api;
    protected String tenant;
    protected JdbcClient db;

    @BeforeEach
    void apiAndTenant() {
        api = new RosterApi(mvc, json);
        tenant = newTenant();
        db = superuserJdbc();
    }

    protected long outboxRows(String employeeId) {
        return db.sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND employee_id = ?")
                .params(tenant, employeeId).query(Long.class).single();
    }

    protected long historyRows(String employeeId) {
        return db.sql("SELECT count(*) FROM employee_history WHERE tenant_id = ? AND employee_id = ?")
                .params(tenant, employeeId).query(Long.class).single();
    }
}
