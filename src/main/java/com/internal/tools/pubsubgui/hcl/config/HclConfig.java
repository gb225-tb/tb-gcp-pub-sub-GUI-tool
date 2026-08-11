package com.internal.tools.pubsubgui.hcl.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * All configuration for the HCL -&gt; Config Catalog mapping. In the GUI tool this is assembled per
 * environment from {@code HclProperties}; {@link Queries} and {@link AttributeMappings} are loaded from
 * the classpath ({@code hcl/hcl-queries.yml}, {@code hcl/hcl-attributes.yml}) by {@link HclConfigLoader}.
 *
 * <p>Ported from tb-catalog-data-processor. Only the read + build parts are used here; the target
 * database blocks exist so the copied {@code Db2ProductReader}/{@code DocumentMappers} compile unchanged.
 */
@Data
@NoArgsConstructor
public class HclConfig {

    private Db2 db2 = new Db2();
    private TargetDataBase itemConfig = new TargetDataBase();
    private TargetDataBase inventoryConfig = new TargetDataBase();
    private Migration migration = new Migration();

    /** HCL Commerce DB2 (WCS schema) source. */
    @Data
    @NoArgsConstructor
    public static class Db2 {
        private String url;
        private String user;
        private String password;
        private String currentSchema = "WCS";
        private String driverClass = "com.ibm.db2.jcc.DB2Driver";
        private int fetchSize = 1000;
    }

    /** A target database: connection, collection names, batch size. Unused for writes in the tool. */
    @Data
    @NoArgsConstructor
    public static class TargetDataBase {
        private String uri;
        private String database;
        private int batchSize = 1000;
        private Collections collections = new Collections();
    }

    /** Destination collection names (only the names differ from the canonical schema). */
    @Data
    @NoArgsConstructor
    public static class Collections {
        private String product;
        private String variant;
        private String sku;
        private String price;
        private String rating;
        private String enrichedProduct;
        private String item;
    }

    /** Business literals that drive document derivation, externalized so they are tunable per environment. */
    @Data
    @NoArgsConstructor
    public static class Migration {
        private String banner = "TMW";
        private String auditActor = "HCLDataMigrationProcessor";
        /** Product part-number prefixes whose variants are NOT given a derived colorCode. */
        private List<String> colorCodeExcludedPrefixes = new ArrayList<>();
        private String listPriceType = "TMWCASList";
        private String salePriceType = "TMWSalePrice";
    }

    /**
     * DB2 (WCS) query templates. Token conventions used by {@code Db2ProductReader}:
     * {@code {PRODUCT_ID}} = one product CATENTRY_ID, {@code {IDS}} = comma-separated CATENTRY_IDs.
     */
    @Data
    @NoArgsConstructor
    public static class Queries {
        private String resolveProductCatEntryIdByPartNumber;
        private String seedProductCatEntryIds;
        private String relationsByProduct;
        private String detailsByIds;
        private String seoUrlsByIds;
        private String associationsByIds;
        private String pricesByIds;
        private String attributesByIds;
        /** Category -> products (Categories view). Optional; only required when that view is used. */
        private String resolveCatGroupById;
        private String resolveCatGroupByIdentifier;
        private String countProductsInCategory;
        private String listProductsInCategory;
    }

    /** Data-driven HCL attribute -&gt; field mappings per document type. */
    @Data
    @NoArgsConstructor
    public static class AttributeMappings {
        private List<Entry> product = new ArrayList<>();
        private List<Entry> variant = new ArrayList<>();
        private List<Entry> sku = new ArrayList<>();
        private List<Entry> enrichedProduct = new ArrayList<>();
        private List<Entry> rating = new ArrayList<>();
    }

    /**
     * One attribute mapping rule. {@code type}: string (default) | double | integer | boolean |
     * stringSet. {@code source}: desc (default) | val. {@code truthy}: case-insensitive true-tokens for
     * boolean (default {@code ["1"]}).
     */
    @Data
    @NoArgsConstructor
    public static class Entry implements Serializable {
        private static final long serialVersionUID = 1L;
        private String hcl;
        private String field;
        private String type = "string";
        private String source = "desc";
        private List<String> truthy = new ArrayList<>();
    }
}
