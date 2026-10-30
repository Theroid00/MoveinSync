package com.moveinsync.rostersync.roster.core;

/** A rejected field: its name and an error code. Never the rejected value. */
public record FieldError(String field, String code) {
}
