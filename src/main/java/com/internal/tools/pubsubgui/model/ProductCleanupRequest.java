package com.internal.tools.pubsubgui.model;

import java.util.List;

/**
 * Payload for the Product Clean Up delete action: purge a productId from a set
 * of (database, collection) targets within one environment.
 *
 * @param env       environment name (e.g. Dev / QA / Perf)
 * @param productId the productId whose documents should be deleted
 * @param targets   the database + collection pairs to delete from
 */
public record ProductCleanupRequest(String env, String productId, List<Target> targets) {

    /** A single database + collection to purge. */
    public record Target(String database, String collection) {
    }
}
