package io.github.drompincen.javaclawv1.protocol.api;

public enum DeltaType {
    MISSING_EPIC,
    DATE_DRIFT,
    OWNER_MISMATCH,
    SCOPE_MISMATCH,
    DEPENDENCY_MISMATCH,
    COVERAGE_GAP,
    ORPHANED_WORK,
    CAPACITY_OVERLOAD,
    STALE_ARTIFACT,
    PRIORITY_MISMATCH,
    STATUS_MISMATCH,
    DUPLICATE_ENTRY
}
