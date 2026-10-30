package com.moveinsync.rostersync.contract;

/** Downstream change-event types published through the roster outbox. */
public enum EventType {
    EmployeeCreated,
    EmployeeUpdated,
    EmployeeDeactivated,
    EmployeeReactivated,
    EmployeeVersionAdvanced,
    EmployeeTransportUpdated
}
