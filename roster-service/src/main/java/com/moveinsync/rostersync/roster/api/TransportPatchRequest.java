package com.moveinsync.rostersync.roster.api;

/**
 * Transport-admin fields only. With fail-on-unknown-properties, any HR field in the body is a 422
 * UNKNOWN_FIELD: the transport endpoint cannot write HR data by construction.
 */
public record TransportPatchRequest(String pickupPoint, String specialAssistance) {
}
