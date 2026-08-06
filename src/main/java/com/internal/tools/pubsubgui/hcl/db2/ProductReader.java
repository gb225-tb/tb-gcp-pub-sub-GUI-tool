package com.internal.tools.pubsubgui.hcl.db2;

import com.internal.tools.pubsubgui.hcl.model.ProductBundle;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads HCL source data. Ported from tb-catalog-data-processor; the GUI tool owns the {@link Connection}
 * lifecycle in {@code HclBuildService} (open per request, close in a finally block).
 */
public interface ProductReader extends Serializable {

    Connection openConnection() throws SQLException;

    List<Long> seedProductIds(Connection connection) throws SQLException;

    ProductBundle fetchProduct(Connection connection, Long productCatEntryId) throws SQLException;

    /** Resolves a product part-number to its CATENTRY_ID (ProductBean), or {@code null} if not found. */
    Long resolveProductCatEntryId(Connection connection, String partNumber) throws SQLException;
}
