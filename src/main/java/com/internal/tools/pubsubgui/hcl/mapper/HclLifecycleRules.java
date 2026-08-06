package com.internal.tools.pubsubgui.hcl.mapper;

import com.internal.tools.pubsubgui.hcl.model.ProductBundle.CatalogEntry;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;

/**
 * Derives the Config-Catalog {@code status}, {@code startDate}, {@code publishedAt}, and {@code endDate}
 * for an HCL catalog entry. Ported verbatim from tb-catalog-data-processor.
 */
public final class HclLifecycleRules {

    public static final String STATUS_ACTIVE = DocumentMappers.STATUS_ACTIVE;
    public static final String STATUS_INACTIVE = DocumentMappers.STATUS_INACTIVE;

    private HclLifecycleRules() {
    }

    /** {@code true} when both CATENTRY.FIELD1 and CATENTRY.FIELD2 equal 1. */
    public static boolean bothFields(CatalogEntry details) {
        return Objects.nonNull(details) && isOne(details.getField1()) && isOne(details.getField2());
    }

    /** {@code true} when CATENTDESC.PUBLISHED is {@code "1"}; {@code "0"}/null are treated as not published. */
    public static boolean isPublished(CatalogEntry details) {
        return Objects.nonNull(details) && "1".equals(details.getProductStatus());
    }

    /** Variant / EnrichedProduct {@code status}: {@code active} iff PUBLISHED == 1, else {@code inactive}. */
    public static String variantStatus(CatalogEntry details) {
        return isPublished(details) ? STATUS_ACTIVE : STATUS_INACTIVE;
    }

    /** SKU {@code status}: {@code active} iff CATENTDESC.PUBLISHED == 1, else {@code inactive}. */
    public static String skuStatus(CatalogEntry details) {
        return isPublished(details) ? STATUS_ACTIVE : STATUS_INACTIVE;
    }

    /** {@code startDate}: STARTDATE -&gt; AVAILABILITYDATE -&gt; LASTUPDATE -&gt; now. Never null. */
    public static Date resolvedStartDate(CatalogEntry details, LocalDateTime now) {
        if (Objects.nonNull(details)) {
            if (Objects.nonNull(details.getStartDate())) {
                return details.getStartDate();
            }
            if (Objects.nonNull(details.getAvailabilityDate())) {
                return details.getAvailabilityDate();
            }
            if (Objects.nonNull(details.getLastUpdate())) {
                return details.getLastUpdate();
            }
        }
        return toDate(now);
    }

    /** {@code publishedAt}: AVAILABILITYDATE -&gt; LASTUPDATE -&gt; now. Never null. */
    public static Date resolvedPublishedAt(CatalogEntry details, LocalDateTime now) {
        if (Objects.nonNull(details)) {
            if (Objects.nonNull(details.getAvailabilityDate())) {
                return details.getAvailabilityDate();
            }
            if (Objects.nonNull(details.getLastUpdate())) {
                return details.getLastUpdate();
            }
        }
        return toDate(now);
    }

    /** Inactive Variant / EnrichedProduct {@code endDate}: ENDDATE -&gt; LASTUPDATE -&gt; now. Never null. */
    public static Date variantEndDate(CatalogEntry details, LocalDateTime now) {
        if (Objects.nonNull(details)) {
            if (Objects.nonNull(details.getEndDate())) {
                return details.getEndDate();
            }
            if (Objects.nonNull(details.getLastUpdate())) {
                return details.getLastUpdate();
            }
        }
        return toDate(now);
    }

    /** Inactive SKU {@code endDate}: ENDDATE -&gt; now. Never null. */
    public static Date skuEndDate(CatalogEntry details, LocalDateTime now) {
        if (Objects.nonNull(details) && Objects.nonNull(details.getEndDate())) {
            return details.getEndDate();
        }
        return toDate(now);
    }

    /**
     * Reporting lifecycle bucket for a single catalog entry, mirroring the DB2 report {@code CASE}.
     */
    public static LifecycleState lifecycleState(CatalogEntry details) {
        if (bothFields(details)) {
            return isPublished(details) ? LifecycleState.ACTIVE_PUBLISHED : LifecycleState.INACTIVE_DISCONTINUED;
        }
        return LifecycleState.ACTIVE_NOT_PUBLISHED;
    }

    /** Truth-table buckets used for the migration lifecycle report counters (matches the DB2 report). */
    public enum LifecycleState {
        ACTIVE_PUBLISHED,
        INACTIVE_DISCONTINUED,
        ACTIVE_NOT_PUBLISHED
    }

    private static Date toDate(LocalDateTime now) {
        return Date.from(now.toInstant(ZoneOffset.UTC));
    }

    private static boolean isOne(Integer value) {
        return Objects.nonNull(value) && value == 1;
    }
}
