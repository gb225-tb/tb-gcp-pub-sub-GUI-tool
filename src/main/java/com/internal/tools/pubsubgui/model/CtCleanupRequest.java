package com.internal.tools.pubsubgui.model;

import java.util.List;

/**
 * Payload for the CT Clean Up delete action: remove a product tree from commercetools for one
 * environment. Deletion is destructive and cannot be undone.
 *
 * @param env           environment name (Dev / QA / Perf)
 * @param productId     the catalog productId (CT product key) being cleaned up
 * @param deleteProduct whether to delete the master {@code tb-product-type} product
 * @param variantIds    CT ids of the color-variant products to delete
 */
public record CtCleanupRequest(String env, String productId, boolean deleteProduct, List<String> variantIds) {

    public List<String> variantIds() {
        return variantIds == null ? List.of() : variantIds;
    }
}
