package com.internal.tools.pubsubgui.hcl.support;

import java.util.Collection;
import java.util.Objects;

/**
 * Classifies the {@code source} of a catalog entity from a SKU id and rolls that classification up to
 * parent Variant / Product docs.
 *
 * <p>Ported from tb-catalog-data-processor with the four source constants + {@code TUX} inlined (the
 * {@code CatalogDataProcessorConstants} dependency is dropped).
 *
 * <p>The rules operate on the <b>SKU id</b>:
 * <ul>
 *   <li>SKU id starts with {@code TMW00} &rarr; {@code manual}</li>
 *   <li>SKU id contains {@code TUX} and contains {@code LOOK} &rarr; {@code siteops}</li>
 *   <li>SKU id contains {@code TUX} but not {@code LOOK} &rarr; {@code tuxoracle}</li>
 *   <li>otherwise &rarr; {@code universe}</li>
 * </ul>
 * A Variant's source is rolled up from its SKUs and a Product's from its Variants, with precedence
 * {@code manual > siteops > tuxoracle > universe}.
 */
public final class CatalogSourceClassifier {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_SITEOPS = "siteops";
    public static final String SOURCE_TUXORACLE = "tuxoracle";
    public static final String SOURCE_UNIVERSE = "universe";
    public static final String TUX = "TUX";

    /** SKU id prefix identifying manually-authored (gift-card / VAS style) items. */
    static final String MANUAL_SKU_PREFIX = "TMW00";
    /** SKU id token identifying rental look bundles (paired with the {@code TUX} token). */
    static final String LOOK_TOKEN = "LOOK";

    private CatalogSourceClassifier() {
    }

    /** Classifies a single SKU by its id. A blank id defaults to {@code universe}. */
    public static String forSkuId(String skuId) {
        if (Objects.isNull(skuId) || skuId.isBlank()) {
            return SOURCE_UNIVERSE;
        }
        String id = skuId.trim();
        if (id.startsWith(MANUAL_SKU_PREFIX)) {
            return SOURCE_MANUAL;
        }
        if (id.contains(TUX)) {
            return id.contains(LOOK_TOKEN) ? SOURCE_SITEOPS : SOURCE_TUXORACLE;
        }
        return SOURCE_UNIVERSE;
    }

    /**
     * Rolls a set of child sources up to a parent with precedence
     * {@code manual > siteops > tuxoracle > universe}. An empty/blank input defaults to {@code universe}.
     */
    public static String rollup(Collection<String> childSources) {
        if (Objects.isNull(childSources) || childSources.isEmpty()) {
            return SOURCE_UNIVERSE;
        }
        boolean siteops = false;
        boolean tuxoracle = false;
        for (String source : childSources) {
            if (SOURCE_MANUAL.equals(source)) {
                return SOURCE_MANUAL;
            }
            if (SOURCE_SITEOPS.equals(source)) {
                siteops = true;
            }
            if (SOURCE_TUXORACLE.equals(source)) {
                tuxoracle = true;
            }
        }
        if (siteops) {
            return SOURCE_SITEOPS;
        }
        if (tuxoracle) {
            return SOURCE_TUXORACLE;
        }
        return SOURCE_UNIVERSE;
    }
}
