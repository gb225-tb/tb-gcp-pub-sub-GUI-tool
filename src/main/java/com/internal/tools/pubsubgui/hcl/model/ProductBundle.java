package com.internal.tools.pubsubgui.hcl.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All HCL source data scoped to a single product catalog entry, assembled by a {@code ProductReader}
 * so a fan-out worker can build every downstream document (Product, Rating, and per-variant
 * Variant / EnrichedProduct / SKU / Price / Item) without any cross-product state.
 *
 * <p>Ported verbatim from tb-catalog-data-processor (HCLDataMigrationProcessor) so the assembled
 * bundle is byte-for-byte faithful to the migration. The GUI tool only reads and builds — it never
 * writes to the config catalog.
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductBundle {

    private Long productCatEntryId;

    /** catEntryId -&gt; part number for product, every variant, and every SKU in the subtree. */
    private Map<Long, String> catEntryIdToPartNumber = new LinkedHashMap<>();

    /** catEntryId -&gt; CATENTRY/CATENTDESC details for product, variants, and SKUs. */
    private Map<Long, CatalogEntry> detailsByCatEntryId = new LinkedHashMap<>();

    /** catEntryId -&gt; SEO url keyword (product and variant levels). */
    private Map<Long, String> seoUrlByCatEntryId = new LinkedHashMap<>();

    /** variant catEntryId -&gt; its merchandising associations (UPSELL / ACCESSORY). */
    private Map<Long, List<Association>> associationsByCatEntryId = new LinkedHashMap<>();

    /** SKU catEntryId -&gt; resolved price triple. */
    private Map<Long, Price> priceByCatEntryId = new LinkedHashMap<>();

    /** catEntryId -&gt; attribute values (product, variant, and SKU levels). */
    private Map<Long, List<AttributeValue>> attributesByCatEntryId = new LinkedHashMap<>();

    /** variant catEntryId -&gt; ordered list of its SKU catEntryIds. */
    private Map<Long, List<Long>> skuCatEntryIdsByVariantCatEntryId = new LinkedHashMap<>();

    public CatalogEntry details(Long catEntryId) {
        return detailsByCatEntryId.get(catEntryId);
    }

    public String partNumber(Long catEntryId) {
        return catEntryIdToPartNumber.get(catEntryId);
    }

    /** One row from the CATENTRY/CATENTDESC join (product, variant, or SKU level). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class CatalogEntry {
        private Long catEntryId;
        private String partNumber;
        private String mfName;
        /** CATENTRY.MFPARTNUMBER — the SKU UPC in HCL. */
        private String mfPartNumber;
        private String name;
        private String thumbnail;
        private String fullImage;
        private String longDescription;
        private String shortDescription;
        private String taxCode;
        /**
         * CATENTDESC.PUBLISHED ({@code "1"} = published). Together with {@link #field1} and
         * {@link #field2} it drives the document {@code status} truth table (see {@code HclLifecycleRules}).
         */
        private String productStatus;
        /** CATENTRY.FIELD1 — part of the status/publishedAt truth table ({@code FIELD1 == 1 && FIELD2 == 1}). */
        private Integer field1;
        /** CATENTRY.FIELD2 — part of the status/publishedAt truth table ({@code FIELD1 == 1 && FIELD2 == 1}). */
        private Integer field2;
        /** CATENTDESC.AVAILABILITYDATE — source for {@code publishedAt} when both fields are set (else current date). */
        private Date availabilityDate;
        /** CATENTRY.STARTDATE — written as the document {@code startDate} Date. */
        private Date startDate;
        /** CATENTRY.ENDDATE — written as the document {@code endDate} Date. */
        private Date endDate;
        /** CATENTRY.LASTUPDATE — fallback source for {@code startDate}/{@code publishedAt}/{@code endDate}. */
        private Date lastUpdate;
    }

    /** One catalog-entry attribute value (ATTR.IDENTIFIER + ATTRVAL.IDENTIFIER + ATTRVALDESC.VALUE). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class AttributeValue {
        private String attributeName;
        private String valueFromVal;
        private String valueFromDesc;
    }

    /** One variant merchandising association (MASSOCTYPE_ID = UPSELL / ACCESSORY). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Association {
        private String associatedPartNumber;
        private String associationType;
    }

    /** Resolved price triple for a SKU; {@code finalPrice} follows promo &gt; sale &gt; list. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Price {
        private Double listPrice;
        private Double salePrice;
        private Double promoPrice;
        private Double finalPrice;
    }
}
