package com.internal.tools.pubsubgui.hcl.mapper;

import com.internal.tools.pubsubgui.hcl.config.HclConfig;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle.Association;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle.AttributeValue;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle.CatalogEntry;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle.Price;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Single, self-contained mapper that turns a {@link ProductBundle} into the HCL Config-Catalog
 * documents (Product, Rating, Variant, EnrichedProduct, SKU, Price, Item).
 *
 * <p>Ported verbatim from tb-catalog-data-processor so each document is schema-identical to the
 * migration's output. The GUI tool builds these for display only — it never writes them.
 */
public class DocumentMappers implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Canonical Config-Catalog field names (schema parity with existing collections) ──
    public static final String ID = "_id";
    public static final String STATUS = "status";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";
    /** Provenance of the doc (manual / siteops / universe) - see {@code CatalogSourceClassifier}. */
    public static final String SOURCE = "source";
    public static final String PRODUCT_ID = "productId";
    public static final String VARIANT_ID = "variantId";
    /** EnrichedProduct self-reference key — canonical field name for variant identity. */
    public static final String ENRICHED_VARIANT_ID = "variantId";
    public static final String SKU = "sku";
    public static final String BANNER = "banner";
    public static final String PRODUCT_NAME = "productName";
    public static final String PRODUCT_DESCRIPTION = "productDescription";
    public static final String SEO_URL = "seoUrl";
    public static final String DIVISION = "division";
    public static final String DIVISION_DESCRIPTION = "divisionDescription";
    public static final String OCCASION = "occasion";
    public static final String RENTAL_FIT = "rentalFit";
    public static final String FABRIC = "fabric";
    public static final String PATTERN = "pattern";
    public static final String CARE_INSTRUCTIONS = "careInstructions";
    public static final String FEATURES = "features";
    public static final String SPECIAL_FEATURES = "specialFeatures";
    public static final String SPECIAL_FEATURES_PGP = "specialFeaturesPGP";
    public static final String BIG_AND_TALL_FIT = "bigAndTallFit";
    public static final String RENTAL_BULLET_1 = "rentalBullet1";
    public static final String RENTAL_BULLET_2 = "rentalBullet2";
    public static final String RENTAL_BULLET_3 = "rentalBullet3";
    public static final String STYLE_FOR_BULLET = "styleForBullet";
    public static final String COLOR_CODE = "colorCode";
    public static final String PRODUCT_ASSOCIATION = "productAssociation";
    public static final String ADDITIONAL_ASSOCIATION = "additionalAssociation";
    public static final String MAIN_IMAGE = "mainImage";
    public static final String ALT_IMAGE = "altImage";
    public static final String SWATCH_IMAGE = "swatchImage";
    public static final String BT_IMAGE = "btImage";
    public static final String VIDEO = "video";
    public static final String VIDEO_VID1 = "VID1";
    public static final String AVERAGE_RATING = "averageRating";
    public static final String AVERAGE_ROUNDED_RATING = "averageRoundedRating";
    public static final String RATING_AND_REVIEW = "ratingAndReview";
    public static final String TAX_CODE = "taxCode";
    /** SKU UPC, sourced from HCL CATENTRY.MFPARTNUMBER. */
    public static final String UPC = "upc";
    public static final String IS_CLEARANCE = "isClearance";
    public static final String LIST_PRICE = "listPrice";
    public static final String SALE_PRICE = "salePrice";
    public static final String PROMO_PRICE = "promoPrice";
    public static final String PUBLISHED_AT = "publishedAt";
    public static final String START_DATE = "startDate";
    public static final String END_DATE = "endDate";
    public static final String CREATED_BY = "createdBy";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_BY = "updatedBy";
    public static final String UPDATED_AT = "updatedAt";

    // ── HCL attribute identifiers handled directly in code (not via hcl-attributes.yml) ──
    private static final String ATTR_DIVISION = "Division";
    private static final String ATTR_OCCASION = "Occasion";
    private static final String ATTR_RENTAL_FIT = "RentalFit";
    private static final String ATTR_FEATURES = "Features";
    private static final String ATTR_SPECIAL_FEATURES = "SpecialFeatures";
    private static final String ATTR_FABRIC = "Fabric";
    private static final String ATTR_PATTERN = "Pattern";
    private static final String ATTR_CARE_INSTRUCTIONS = "CareInstructions";
    private static final String ATTR_STYLE_FOR_BULLET = "StyleForBullet";
    private static final String ATTR_SPECIAL_FEATURES_PGP = "SpecialFeaturesPGP";
    private static final String ATTR_BIG_AND_TALL_FIT = "BigAndTallFit";
    private static final String ATTR_RENTAL_BULLET_1 = "RentalBullet1";
    private static final String ATTR_RENTAL_BULLET_2 = "RentalBullet2";
    private static final String ATTR_RENTAL_BULLET_3 = "RentalBullet3";
    private static final String ASSOC_UPSELL = "UPSELL";
    private static final String ASSOC_ACCESSORY = "ACCESSORY";

    private final String banner;
    private final String auditActor;
    private final List<String> colorCodeExcludedPrefixes;

    private final Applier productApplier;
    private final Applier variantApplier;
    private final Applier skuApplier;
    private final Applier enrichedApplier;
    private final Applier ratingApplier;

    private DocumentMappers(String banner, String auditActor, List<String> colorCodeExcludedPrefixes,
                            HclConfig.AttributeMappings mappings) {
        this.banner = banner;
        this.auditActor = auditActor;
        this.colorCodeExcludedPrefixes = Objects.isNull(colorCodeExcludedPrefixes)
                ? new ArrayList<>() : new ArrayList<>(colorCodeExcludedPrefixes);
        this.productApplier = new Applier(mappings.getProduct());
        this.variantApplier = new Applier(mappings.getVariant());
        this.skuApplier = new Applier(mappings.getSku());
        this.enrichedApplier = new Applier(mappings.getEnrichedProduct());
        this.ratingApplier = new Applier(mappings.getRating());
    }

    /** Builds a {@link DocumentMappers} from the loaded config + attribute mappings. */
    public static DocumentMappers from(HclConfig config, HclConfig.AttributeMappings mappings) {
        return new DocumentMappers(
                config.getMigration().getBanner(),
                config.getMigration().getAuditActor(),
                config.getMigration().getColorCodeExcludedPrefixes(),
                mappings);
    }

    /** Mutable holder for the product-level attributes reused by Variant and EnrichedProduct. */
    public static final class ProductSharedAttributes implements Serializable {
        private static final long serialVersionUID = 1L;
        public String fabric;
        public String pattern;
        public String careInstructions;
        public String styleForBullet;
        public String specialFeaturesPGP;
        public String bigAndTallFit;
        public String rentalBullet1;
        public String rentalBullet2;
        public String rentalBullet3;
        public Set<String> features = new LinkedHashSet<>();
        public Set<String> specialFeatures = new LinkedHashSet<>();
    }

    // ── Product + Rating ──────────────────────────────────────────────────────

    public Document buildProduct(ProductBundle bundle, Long productCatEntryId, LocalDateTime now,
                                 ProductSharedAttributes shared) {
        String productId = bundle.partNumber(productCatEntryId);
        CatalogEntry details = bundle.details(productCatEntryId);
        List<AttributeValue> attrs = bundle.getAttributesByCatEntryId().get(productCatEntryId);

        Document doc = new Document();
        doc.put(ID, productId);
        doc.put(PRODUCT_ID, productId);
        doc.put(BANNER, banner);
        doc.put(PRODUCT_NAME, details.getName());
        doc.put(PRODUCT_DESCRIPTION, details.getLongDescription());
        String seoUrl = bundle.getSeoUrlByCatEntryId().get(productCatEntryId);
        if (Objects.nonNull(seoUrl)) {
            doc.put(SEO_URL, seoUrl);
        }

        productApplier.apply(attrs, doc);

        Set<String> occasion = new LinkedHashSet<>();
        Set<String> rentalFit = new LinkedHashSet<>();
        if (Objects.nonNull(attrs)) {
            for (AttributeValue row : attrs) {
                String desc = row.getValueFromDesc();
                switch (row.getAttributeName()) {
                    case ATTR_DIVISION:
                        doc.put(DIVISION, row.getValueFromVal());
                        doc.put(DIVISION_DESCRIPTION, desc);
                        break;
                    case ATTR_OCCASION:
                        if (Objects.nonNull(desc)) {
                            occasion.add(desc);
                        }
                        break;
                    case ATTR_RENTAL_FIT:
                        if (Objects.nonNull(desc)) {
                            rentalFit.add(desc);
                        }
                        break;
                    case ATTR_FEATURES:
                        if (Objects.nonNull(desc)) {
                            shared.features.add(desc);
                        }
                        break;
                    case ATTR_SPECIAL_FEATURES:
                        if (Objects.nonNull(desc)) {
                            shared.specialFeatures.add(desc);
                        }
                        break;
                    case ATTR_FABRIC:
                        shared.fabric = desc;
                        break;
                    case ATTR_PATTERN:
                        shared.pattern = desc;
                        break;
                    case ATTR_CARE_INSTRUCTIONS:
                        shared.careInstructions = desc;
                        break;
                    case ATTR_STYLE_FOR_BULLET:
                        shared.styleForBullet = desc;
                        break;
                    case ATTR_SPECIAL_FEATURES_PGP:
                        shared.specialFeaturesPGP = desc;
                        break;
                    case ATTR_BIG_AND_TALL_FIT:
                        shared.bigAndTallFit = desc;
                        break;
                    case ATTR_RENTAL_BULLET_1:
                        shared.rentalBullet1 = desc;
                        break;
                    case ATTR_RENTAL_BULLET_2:
                        shared.rentalBullet2 = desc;
                        break;
                    case ATTR_RENTAL_BULLET_3:
                        shared.rentalBullet3 = desc;
                        break;
                    default:
                        break;
                }
            }
        }
        if (!occasion.isEmpty()) {
            doc.put(OCCASION, occasion);
        }
        if (!rentalFit.isEmpty()) {
            doc.put(RENTAL_FIT, rentalFit);
        }

        putAudit(doc, now);
        return doc;
    }

    /**
     * Builds the Rating document. The caller should only persist it when it actually carries an
     * {@code averageRating}; use {@link #hasRating(Document)}.
     */
    public Document buildRating(ProductBundle bundle, Long productCatEntryId, LocalDateTime now) {
        String productId = bundle.partNumber(productCatEntryId);
        List<AttributeValue> attrs = bundle.getAttributesByCatEntryId().get(productCatEntryId);
        Document doc = new Document();
        doc.put(ID, productId);
        doc.put(PRODUCT_ID, productId);
        doc.put(BANNER, banner);
        ratingApplier.apply(attrs, doc);
        toDecimal128(doc, AVERAGE_RATING);
        toDecimal128(doc, AVERAGE_ROUNDED_RATING);
        toDecimal128(doc, RATING_AND_REVIEW);
        putAudit(doc, now);
        return doc;
    }

    /** Rewrites a numeric document field to {@link Decimal128}, preserving its value/scale. */
    private static void toDecimal128(Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof Number) {
            doc.put(key, new Decimal128(new BigDecimal(value.toString())));
        }
    }

    public static boolean hasRating(Document rating) {
        return Objects.nonNull(rating) && rating.containsKey(AVERAGE_RATING);
    }

    /**
     * An EnrichedProduct is only persisted when it is publish-ready: {@code productName} and
     * {@code productDescription} must be present and at least one image ({@code mainImage} or
     * {@code altImage}) must be present.
     */
    public static boolean isEnrichedPublishReady(Document enriched) {
        return Objects.nonNull(enriched)
                && StringUtils.isNotBlank(enriched.getString(PRODUCT_NAME))
                && StringUtils.isNotBlank(enriched.getString(PRODUCT_DESCRIPTION))
                && (StringUtils.isNotBlank(enriched.getString(MAIN_IMAGE))
                    || StringUtils.isNotBlank(enriched.getString(ALT_IMAGE)));
    }

    // ── Variant ────────────────────────────────────────────────────────────────

    public Document buildVariant(ProductBundle bundle, Long variantCatEntryId, String productId,
                                 ProductSharedAttributes shared, LocalDateTime now) {
        String variantId = bundle.partNumber(variantCatEntryId);
        CatalogEntry details = bundle.details(variantCatEntryId);
        List<AttributeValue> attrs = bundle.getAttributesByCatEntryId().get(variantCatEntryId);
        List<Association> associations = bundle.getAssociationsByCatEntryId().get(variantCatEntryId);

        Document doc = new Document();
        doc.put(ID, variantId);
        doc.put(PRODUCT_ID, productId);
        doc.put(VARIANT_ID, variantId);
        doc.put(BANNER, banner);

        if (Objects.nonNull(shared.careInstructions)) {
            doc.put(CARE_INSTRUCTIONS, shared.careInstructions);
        }
        if (!isColorCodeExcluded(productId) && Objects.nonNull(variantId) && variantId.length() >= 2) {
            doc.put(COLOR_CODE, variantId.substring(variantId.length() - 2));
        }
        if (Objects.nonNull(associations) && !associations.isEmpty()) {
            doc.put(PRODUCT_ASSOCIATION, associatedPartNumbersOfType(associations, ASSOC_UPSELL));
        }
        if (Objects.nonNull(shared.fabric)) {
            doc.put(FABRIC, shared.fabric);
        }
        if (Objects.nonNull(shared.pattern)) {
            doc.put(PATTERN, shared.pattern);
        }
        if (Objects.nonNull(shared.features) && !shared.features.isEmpty()) {
            doc.put(FEATURES, shared.features);
        }

        variantApplier.apply(attrs, doc);
        stampVariantLifecycle(doc, details, now);
        return doc;
    }

    // ── EnrichedProduct ──────────────────────────────────────────────────────

    public Document buildEnrichedProduct(ProductBundle bundle, Long variantCatEntryId, String productId,
                                         ProductSharedAttributes shared, LocalDateTime now) {
        String variantId = bundle.partNumber(variantCatEntryId);
        CatalogEntry details = bundle.details(variantCatEntryId);
        List<AttributeValue> attrs = bundle.getAttributesByCatEntryId().get(variantCatEntryId);
        List<Association> associations = bundle.getAssociationsByCatEntryId().get(variantCatEntryId);
        String seoUrl = bundle.getSeoUrlByCatEntryId().get(variantCatEntryId);

        Document doc = new Document();
        doc.put(ID, variantId);
        doc.put(PRODUCT_ID, productId);
        doc.put(ENRICHED_VARIANT_ID, variantId);
        doc.put(BANNER, banner);

        doc.put(PRODUCT_NAME, details.getName());
        doc.put(PRODUCT_DESCRIPTION, details.getLongDescription());
        if (Objects.nonNull(seoUrl)) {
            doc.put(SEO_URL, seoUrl);
        }
        if (Objects.nonNull(shared.fabric)) {
            doc.put(FABRIC, shared.fabric);
        }
        if (Objects.nonNull(shared.pattern)) {
            doc.put(PATTERN, shared.pattern);
        }
        if (Objects.nonNull(shared.specialFeatures) && !shared.specialFeatures.isEmpty()) {
            doc.put(SPECIAL_FEATURES, shared.specialFeatures);
        }
        if (Objects.nonNull(shared.specialFeaturesPGP)) {
            doc.put(SPECIAL_FEATURES_PGP, shared.specialFeaturesPGP);
        }
        if (Objects.nonNull(shared.bigAndTallFit)) {
            doc.put(BIG_AND_TALL_FIT, shared.bigAndTallFit);
        }
        if (Objects.nonNull(shared.rentalBullet1)) {
            doc.put(RENTAL_BULLET_1, shared.rentalBullet1);
        }
        if (Objects.nonNull(shared.rentalBullet2)) {
            doc.put(RENTAL_BULLET_2, shared.rentalBullet2);
        }
        if (Objects.nonNull(shared.rentalBullet3)) {
            doc.put(RENTAL_BULLET_3, shared.rentalBullet3);
        }
        if (Objects.nonNull(associations) && !associations.isEmpty()) {
            doc.put(ADDITIONAL_ASSOCIATION, associatedPartNumbersOfType(associations, ASSOC_ACCESSORY));
        }

        enrichedApplier.apply(attrs, doc);

        if (Objects.nonNull(variantId) && variantId.contains("LOOK") && Objects.nonNull(shared.styleForBullet)) {
            doc.put(STYLE_FOR_BULLET, shared.styleForBullet);
        }
        buildImages(details, doc);
        stampVariantLifecycle(doc, details, now);
        return doc;
    }

    private static void buildImages(CatalogEntry details, Document doc) {
        if (Objects.nonNull(details.getThumbnail())) {
            doc.put(MAIN_IMAGE, details.getThumbnail());
            doc.put(SWATCH_IMAGE, details.getThumbnail().replace("_MAIN", "_SW"));
            if (Objects.nonNull(details.getFullImage()) && details.getFullImage().contains("|BT")) {
                doc.put(BT_IMAGE, details.getThumbnail().concat("_BT"));
            }
        }
        if (Objects.nonNull(details.getFullImage())) {
            if (details.getFullImage().contains("VID1")) {
                doc.put(VIDEO, VIDEO_VID1);
            }
            String altImage = details.getFullImage().replace("|BT", "").replace(",VID1", "").replace("VID1", "");
            if (!StringUtils.isEmpty(altImage)) {
                doc.put(ALT_IMAGE, altImage);
            }
        }
    }

    // ── SKU + Price + Item ───────────────────────────────────────────────────

    public Document buildSku(ProductBundle bundle, Long skuCatEntryId, String productId, String variantId,
                             LocalDateTime now) {
        String sku = bundle.partNumber(skuCatEntryId);
        CatalogEntry details = bundle.details(skuCatEntryId);
        List<AttributeValue> attrs = bundle.getAttributesByCatEntryId().get(skuCatEntryId);

        Document doc = new Document();
        doc.put(ID, sku);
        doc.put(PRODUCT_ID, productId);
        doc.put(VARIANT_ID, variantId);
        doc.put(SKU, sku);
        doc.put(BANNER, banner);

        stampSkuLifecycle(doc, details, now);
        if (Objects.nonNull(details.getTaxCode())) {
            doc.put(TAX_CODE, details.getTaxCode());
        }
        if (Objects.nonNull(details.getMfPartNumber())) {
            doc.put(UPC, details.getMfPartNumber());
        }
        skuApplier.apply(attrs, doc);
        return doc;
    }

    public Document buildPrice(ProductBundle bundle, Long skuCatEntryId, String productId, String variantId,
                               LocalDateTime now) {
        String sku = bundle.partNumber(skuCatEntryId);
        Price price = bundle.getPriceByCatEntryId().get(skuCatEntryId);
        Document doc = new Document();
        doc.put(ID, sku);
        doc.put(PRODUCT_ID, productId);
        doc.put(VARIANT_ID, variantId);
        doc.put(SKU, sku);
        doc.put(STATUS, HclLifecycleRules.skuStatus(bundle.details(skuCatEntryId)));
        if (Objects.nonNull(price)) {
            if (Objects.nonNull(price.getListPrice())) {
                doc.put(LIST_PRICE, price.getListPrice());
            }
            if (Objects.nonNull(price.getSalePrice())) {
                doc.put(SALE_PRICE, price.getSalePrice());
            }
            if (Objects.nonNull(price.getPromoPrice())) {
                doc.put(PROMO_PRICE, price.getPromoPrice());
            }
        }
        putAudit(doc, now);
        return doc;
    }

    /** Builds the inventory Item document from an already-built SKU document and the product division. */
    public Document buildItem(Document skuDoc, String division, LocalDateTime now) {
        Document item = new Document();
        item.put(ID, skuDoc.get(ID));
        item.put(DIVISION, division);
        item.put(PRODUCT_ID, skuDoc.get(PRODUCT_ID));
        item.put(VARIANT_ID, skuDoc.get(VARIANT_ID));
        item.put(SKU, skuDoc.get(SKU));
        item.put(BANNER, banner);
        item.put(STATUS, skuDoc.get(STATUS));
        if (skuDoc.containsKey(IS_CLEARANCE) && Objects.nonNull(skuDoc.get(IS_CLEARANCE))) {
            item.put(IS_CLEARANCE, skuDoc.get(IS_CLEARANCE));
        }
        item.put(CREATED_BY, auditActor);
        item.put(UPDATED_BY, auditActor);
        item.put(CREATED_AT, now);
        item.put(UPDATED_AT, now);
        return item;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Set<String> associatedPartNumbersOfType(List<Association> associations, String type) {
        Set<String> result = new LinkedHashSet<>();
        for (Association a : associations) {
            String t = a.getAssociationType();
            if (Objects.nonNull(t) && type.equalsIgnoreCase(t.trim())) {
                result.add(a.getAssociatedPartNumber());
            }
        }
        return result;
    }

    private boolean isColorCodeExcluded(String productId) {
        if (Objects.isNull(productId)) {
            return true;
        }
        for (String prefix : colorCodeExcludedPrefixes) {
            if (Objects.nonNull(prefix) && productId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void stampVariantLifecycle(Document doc, CatalogEntry details, LocalDateTime now) {
        String status = HclLifecycleRules.variantStatus(details);
        doc.put(STATUS, status);
        doc.put(START_DATE, HclLifecycleRules.resolvedStartDate(details, now));
        doc.put(PUBLISHED_AT, HclLifecycleRules.resolvedPublishedAt(details, now));
        if (STATUS_INACTIVE.equals(status)) {
            doc.put(END_DATE, HclLifecycleRules.variantEndDate(details, now));
        }
        putAudit(doc, now);
    }

    private void stampSkuLifecycle(Document doc, CatalogEntry details, LocalDateTime now) {
        String status = HclLifecycleRules.skuStatus(details);
        doc.put(STATUS, status);
        doc.put(START_DATE, HclLifecycleRules.resolvedStartDate(details, now));
        if (STATUS_INACTIVE.equals(status)) {
            doc.put(END_DATE, HclLifecycleRules.skuEndDate(details, now));
        }
        putAudit(doc, now);
    }

    private void putAudit(Document doc, LocalDateTime now) {
        doc.put(CREATED_BY, auditActor);
        doc.put(CREATED_AT, now);
        doc.put(UPDATED_BY, auditActor);
        doc.put(UPDATED_AT, now);
    }

    /**
     * Applies a list of {@link HclConfig.Entry} data-driven mappings onto a document with null-safe
     * type coercion.
     */
    static final class Applier implements Serializable {

        private static final long serialVersionUID = 1L;

        private final List<HclConfig.Entry> entries;

        Applier(List<HclConfig.Entry> entries) {
            this.entries = Objects.isNull(entries) ? new ArrayList<>() : new ArrayList<>(entries);
        }

        void apply(List<AttributeValue> attrs, Document doc) {
            if (Objects.isNull(attrs) || attrs.isEmpty() || entries.isEmpty()) {
                return;
            }
            for (HclConfig.Entry entry : entries) {
                applyEntry(entry, attrs, doc);
            }
        }

        private void applyEntry(HclConfig.Entry entry, List<AttributeValue> attrs, Document doc) {
            String type = Objects.isNull(entry.getType()) ? "string" : entry.getType().trim();
            boolean useVal = "val".equalsIgnoreCase(entry.getSource());
            Set<String> set = null;
            for (AttributeValue row : attrs) {
                if (!entry.getHcl().equals(row.getAttributeName())) {
                    continue;
                }
                String raw = useVal ? row.getValueFromVal() : row.getValueFromDesc();
                switch (type) {
                    case "double":
                        Double d = toDouble(raw);
                        if (Objects.nonNull(d)) {
                            doc.put(entry.getField(), d);
                        }
                        break;
                    case "integer":
                        Integer i = toInteger(raw);
                        if (Objects.nonNull(i)) {
                            doc.put(entry.getField(), i);
                        }
                        break;
                    case "boolean":
                        Boolean b = toBoolean(raw, entry.getTruthy());
                        if (Objects.nonNull(b)) {
                            doc.put(entry.getField(), b);
                        }
                        break;
                    case "stringSet":
                        if (Objects.isNull(set)) {
                            set = new LinkedHashSet<>();
                        }
                        if (Objects.nonNull(raw)) {
                            set.add(raw);
                        }
                        break;
                    default:
                        if (Objects.nonNull(raw)) {
                            doc.put(entry.getField(), raw);
                        }
                        break;
                }
            }
            if (Objects.nonNull(set)) {
                doc.put(entry.getField(), set);
            }
        }
    }

    // ── Null-safe value coercion ───────────────────────────────────────────────

    static Double toDouble(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer toInteger(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    static Boolean toBoolean(String value, List<String> truthy) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        List<String> tokens = (Objects.isNull(truthy) || truthy.isEmpty()) ? List.of("1") : truthy;
        for (String token : tokens) {
            if (Objects.nonNull(token) && token.equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }
}
