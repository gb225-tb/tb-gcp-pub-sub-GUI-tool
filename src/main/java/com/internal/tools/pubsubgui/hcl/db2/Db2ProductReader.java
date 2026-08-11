package com.internal.tools.pubsubgui.hcl.db2;

import com.internal.tools.pubsubgui.hcl.config.HclConfig;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB2 implementation of {@link ProductReader}. Per product it runs the relation query, derives the
 * variant/SKU id set, then runs the {@code {IDS}}-scoped detail / SEO / association / price / attribute
 * queries — mirroring the legacy single-product queries so the assembled {@link ProductBundle} is
 * faithful to the original migration.
 *
 * <p>Ported from tb-catalog-data-processor with one addition: {@link #resolveProductCatEntryId} maps a
 * user-typed part-number to a CATENTRY_ID via a parameterized (injection-safe) PreparedStatement.
 */
public class Db2ProductReader implements ProductReader {

    private static final long serialVersionUID = 1L;

    private final String driverClass;
    private final String url;
    private final String user;
    private final String password;
    private final String currentSchema;
    private final int fetchSize;
    private final String listPriceType;
    private final String salePriceType;

    private final String resolveByPartNumber;
    private final String seedQuery;
    private final String relationsByProduct;
    private final String detailsByIds;
    private final String seoUrlsByIds;
    private final String associationsByIds;
    private final String pricesByIds;
    private final String attributesByIds;

    // Category -> products (Categories view). Optional; validated when used.
    private final String resolveCatGroupById;
    private final String resolveCatGroupByIdentifier;
    private final String countProductsInCategory;
    private final String listProductsInCategory;
    private final String listAllProductPartNumbersInCategory;

    public Db2ProductReader(HclConfig config, HclConfig.Queries queries) {
        HclConfig.Db2 db2 = config.getDb2();
        this.driverClass = db2.getDriverClass();
        this.url = db2.getUrl();
        this.user = db2.getUser();
        this.password = db2.getPassword();
        this.currentSchema = db2.getCurrentSchema();
        this.fetchSize = db2.getFetchSize();
        this.listPriceType = config.getMigration().getListPriceType();
        this.salePriceType = config.getMigration().getSalePriceType();
        this.resolveByPartNumber =
                require(queries.getResolveProductCatEntryIdByPartNumber(), "resolveProductCatEntryIdByPartNumber");
        this.seedQuery = require(queries.getSeedProductCatEntryIds(), "seedProductCatEntryIds");
        this.relationsByProduct = require(queries.getRelationsByProduct(), "relationsByProduct");
        this.detailsByIds = require(queries.getDetailsByIds(), "detailsByIds");
        this.seoUrlsByIds = require(queries.getSeoUrlsByIds(), "seoUrlsByIds");
        this.associationsByIds = require(queries.getAssociationsByIds(), "associationsByIds");
        this.pricesByIds = require(queries.getPricesByIds(), "pricesByIds");
        this.attributesByIds = require(queries.getAttributesByIds(), "attributesByIds");
        this.resolveCatGroupById = queries.getResolveCatGroupById();
        this.resolveCatGroupByIdentifier = queries.getResolveCatGroupByIdentifier();
        this.countProductsInCategory = queries.getCountProductsInCategory();
        this.listProductsInCategory = queries.getListProductsInCategory();
        this.listAllProductPartNumbersInCategory = queries.getListAllProductPartNumbersInCategory();
    }

    // ── Category -> products (Categories view) ─────────────────────────────────

    /** A resolved HCL catalog group: its numeric id and identifier string. */
    public record CategoryRef(long catGroupId, String identifier) {
    }

    /** One product row belonging to a category (for the "view data" list). */
    public record CategoryProduct(long catEntryId, String partNumber, String name, String published) {
    }

    /**
     * Resolves a user-typed category value to a CATGROUP. When the value is numeric it is treated as a
     * CATGROUP_ID; otherwise it is looked up by CATGROUP.IDENTIFIER. Returns {@code null} when not found.
     */
    public CategoryRef resolveCategory(Connection connection, String input) throws SQLException {
        if (Objects.isNull(input) || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        Long numeric = tryParseLong(trimmed);
        if (Objects.nonNull(numeric) && Objects.nonNull(resolveCatGroupById)) {
            CategoryRef byId = resolveWith(connection, resolveCatGroupById, numeric);
            if (Objects.nonNull(byId)) {
                return byId;
            }
        }
        if (Objects.nonNull(resolveCatGroupByIdentifier)) {
            CategoryRef byIdentifier = resolveWith(connection, resolveCatGroupByIdentifier, trimmed);
            if (Objects.nonNull(byIdentifier)) {
                return byIdentifier;
            }
        }
        // Fall back to trusting a numeric id even when CATGROUP has no row we can read.
        return Objects.nonNull(numeric) ? new CategoryRef(numeric, null) : null;
    }

    private CategoryRef resolveWith(Connection connection, String sql, Object bind) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (bind instanceof Long l) {
                ps.setLong(1, l);
            } else {
                ps.setString(1, String.valueOf(bind));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    if (rs.wasNull()) {
                        return null;
                    }
                    return new CategoryRef(id, trim(rs.getString(2)));
                }
            }
        }
        return null;
    }

    /** Count of distinct ProductBean catentries in the category. */
    public long countProductsInCategory(Connection connection, long catGroupId) throws SQLException {
        String sql = require(countProductsInCategory, "countProductsInCategory");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, catGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Product rows in the category (capped by the query's FETCH FIRST clause). */
    public List<CategoryProduct> listProductsInCategory(Connection connection, long catGroupId) throws SQLException {
        String sql = require(listProductsInCategory, "listProductsInCategory");
        List<CategoryProduct> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, catGroupId);
            if (fetchSize > 0) {
                ps.setFetchSize(fetchSize);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CategoryProduct(
                            rs.getLong("CATENTRY_ID"),
                            trim(rs.getString("PARTNUMBER")),
                            trim(rs.getString("NAME")),
                            trim(rs.getString("PUBLISHED"))));
                }
            }
        }
        return out;
    }

    /**
     * All distinct product part numbers in the category (uncapped in SQL; the caller bounds the result
     * with {@code maxRows}). Used by the cross-source reconciliation, which needs the full HCL set.
     */
    public List<String> listAllProductPartNumbersInCategory(Connection connection, long catGroupId, int maxRows)
            throws SQLException {
        String sql = require(listAllProductPartNumbersInCategory, "listAllProductPartNumbersInCategory");
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, catGroupId);
            if (fetchSize > 0) {
                ps.setFetchSize(fetchSize);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && (maxRows <= 0 || out.size() < maxRows)) {
                    String part = trim(rs.getString(1));
                    if (Objects.nonNull(part) && !part.isBlank()) {
                        out.add(part);
                    }
                }
            }
        }
        return out;
    }

    private static Long tryParseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Connection openConnection() throws SQLException {
        if (Objects.isNull(url) || url.isBlank()) {
            throw new IllegalStateException("DB2 URL is required (hcl.environments[].db2.url)");
        }
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DB2 JDBC driver not found on classpath: " + driverClass, e);
        }
        Properties props = new Properties();
        putIfPresent(props, "user", user);
        putIfPresent(props, "password", password);
        putIfPresent(props, "currentSchema", currentSchema);
        return DriverManager.getConnection(url, props);
    }

    @Override
    public Long resolveProductCatEntryId(Connection connection, String partNumber) throws SQLException {
        if (Objects.isNull(partNumber) || partNumber.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(resolveByPartNumber)) {
            ps.setString(1, partNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return rs.wasNull() ? null : id;
                }
            }
        }
        return null;
    }

    @Override
    public List<Long> seedProductIds(Connection connection) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(seedQuery)) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    @Override
    public ProductBundle fetchProduct(Connection connection, Long productCatEntryId) throws SQLException {
        ProductBundle bundle = new ProductBundle();
        bundle.setProductCatEntryId(productCatEntryId);

        readRelations(connection, productCatEntryId, bundle);

        Set<Long> allIds = new LinkedHashSet<>(bundle.getCatEntryIdToPartNumber().keySet());
        if (allIds.isEmpty()) {
            return bundle;
        }
        String idList = inList(allIds);
        readDetails(connection, idList, bundle);
        readSeoUrls(connection, idList, bundle);

        Set<Long> variantIds = bundle.getSkuCatEntryIdsByVariantCatEntryId().keySet();
        if (!variantIds.isEmpty()) {
            readAssociations(connection, inList(variantIds), bundle);
        }
        Set<Long> skuIds = bundle.getSkuCatEntryIdsByVariantCatEntryId().values().stream()
                .flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!skuIds.isEmpty()) {
            readPrices(connection, inList(skuIds), bundle);
        }
        readAttributes(connection, idList, bundle);
        return bundle;
    }

    private void readRelations(Connection connection, Long productCatEntryId, ProductBundle bundle)
            throws SQLException {
        String sql = relationsByProduct.replace("{PRODUCT_ID}", Long.toString(productCatEntryId));
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Long productId = rs.getLong(1);
                    String productPart = trim(rs.getString(2));
                    Long variantId = rs.getLong(3);
                    String variantPart = trim(rs.getString(4));
                    Long skuId = rs.getLong(5);
                    String skuPart = trim(rs.getString(6));

                    Map<Long, String> parts = bundle.getCatEntryIdToPartNumber();
                    parts.put(productId, productPart);
                    parts.put(variantId, variantPart);
                    parts.put(skuId, skuPart);

                    List<Long> skus = bundle.getSkuCatEntryIdsByVariantCatEntryId()
                            .computeIfAbsent(variantId, k -> new ArrayList<>());
                    if (!skus.contains(skuId)) {
                        skus.add(skuId);
                    }
                }
            }
        }
    }

    private void readDetails(Connection connection, String idList, ProductBundle bundle) throws SQLException {
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(detailsByIds.replace("{IDS}", idList))) {
                while (rs.next()) {
                    ProductBundle.CatalogEntry e = new ProductBundle.CatalogEntry();
                    e.setCatEntryId(rs.getLong("CATENTRY_ID"));
                    e.setMfName(trim(rs.getString("MFNAME")));
                    e.setMfPartNumber(trim(rs.getString("MFPARTNUMBER")));
                    e.setPartNumber(trim(rs.getString("PARTNUMBER")));
                    e.setName(trim(rs.getString("NAME")));
                    e.setThumbnail(trim(rs.getString("THUMBNAIL")));
                    e.setFullImage(trim(rs.getString("FULLIMAGE")));
                    e.setLongDescription(trim(rs.getString("LONGDESCRIPTION")));
                    e.setShortDescription(trim(rs.getString("SHORTDESCRIPTION")));
                    e.setTaxCode(trim(rs.getString("FIELD5")));
                    e.setProductStatus(trim(rs.getString("PUBLISHED")));
                    e.setField1(readNullableInt(rs, "FIELD1"));
                    e.setField2(readNullableInt(rs, "FIELD2"));
                    java.sql.Timestamp availTs = rs.getTimestamp("AVAILABILITYDATE");
                    e.setAvailabilityDate(Objects.nonNull(availTs) ? new java.util.Date(availTs.getTime()) : null);
                    java.sql.Timestamp startTs = rs.getTimestamp("STARTDATE");
                    e.setStartDate(Objects.nonNull(startTs) ? new java.util.Date(startTs.getTime()) : null);
                    java.sql.Timestamp endTs = rs.getTimestamp("ENDDATE");
                    e.setEndDate(Objects.nonNull(endTs) ? new java.util.Date(endTs.getTime()) : null);
                    java.sql.Timestamp lastUpdateTs = rs.getTimestamp("LASTUPDATE");
                    e.setLastUpdate(Objects.nonNull(lastUpdateTs) ? new java.util.Date(lastUpdateTs.getTime()) : null);
                    bundle.getDetailsByCatEntryId().put(e.getCatEntryId(), e);
                }
            }
        }
    }

    private void readSeoUrls(Connection connection, String idList, ProductBundle bundle) throws SQLException {
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(seoUrlsByIds.replace("{IDS}", idList))) {
                while (rs.next()) {
                    bundle.getSeoUrlByCatEntryId().put(rs.getLong("CATENTRY_ID"), trim(rs.getString("URLKEYWORD")));
                }
            }
        }
    }

    private void readAssociations(Connection connection, String idList, ProductBundle bundle) throws SQLException {
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(associationsByIds.replace("{IDS}", idList))) {
                while (rs.next()) {
                    Long fromCatEntryId = rs.getLong(1);
                    ProductBundle.Association a = new ProductBundle.Association();
                    a.setAssociatedPartNumber(trim(rs.getString(4)));
                    a.setAssociationType(trim(rs.getString(5)));
                    bundle.getAssociationsByCatEntryId()
                            .computeIfAbsent(fromCatEntryId, k -> new ArrayList<>()).add(a);
                }
            }
        }
    }

    private void readPrices(Connection connection, String idList, ProductBundle bundle) throws SQLException {
        Map<Long, ProductBundle.Price> byId = bundle.getPriceByCatEntryId();
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(pricesByIds.replace("{IDS}", idList))) {
                while (rs.next()) {
                    Long catEntryId = rs.getLong("CATENTRY_ID");
                    String priceType = trim(rs.getString("PRICE_TYPE"));
                    double val = rs.getDouble("PRICE");
                    Double price = rs.wasNull() ? null : val;
                    ProductBundle.Price details = byId.computeIfAbsent(catEntryId, k -> new ProductBundle.Price());
                    if (Objects.nonNull(priceType) && priceType.equalsIgnoreCase(listPriceType)) {
                        details.setListPrice(price);
                    } else if (Objects.nonNull(priceType) && priceType.equalsIgnoreCase(salePriceType)) {
                        details.setSalePrice(price);
                    } else {
                        details.setPromoPrice(price);
                    }
                }
            }
        }
        for (ProductBundle.Price d : byId.values()) {
            if (Objects.nonNull(d.getPromoPrice())) {
                d.setFinalPrice(d.getPromoPrice());
            } else if (Objects.nonNull(d.getSalePrice())) {
                d.setFinalPrice(d.getSalePrice());
            } else if (Objects.nonNull(d.getListPrice())) {
                d.setFinalPrice(d.getListPrice());
            } else {
                d.setFinalPrice(0.0);
            }
        }
    }

    private void readAttributes(Connection connection, String idList, ProductBundle bundle) throws SQLException {
        try (Statement st = connection.createStatement()) {
            applyFetchSize(st);
            try (ResultSet rs = st.executeQuery(attributesByIds.replace("{IDS}", idList))) {
                while (rs.next()) {
                    Long catEntryId = rs.getLong(1);
                    ProductBundle.AttributeValue row = new ProductBundle.AttributeValue();
                    row.setAttributeName(trim(rs.getString(2)));
                    row.setValueFromVal(trim(rs.getString(3)));
                    row.setValueFromDesc(trim(rs.getString(4)));
                    bundle.getAttributesByCatEntryId()
                            .computeIfAbsent(catEntryId, k -> new ArrayList<>()).add(row);
                }
            }
        }
    }

    private void applyFetchSize(Statement st) throws SQLException {
        if (fetchSize > 0) {
            st.setFetchSize(fetchSize);
        }
    }

    static String inList(Set<Long> ids) {
        return ids.stream().map(id -> Long.toString(id)).collect(Collectors.joining(","));
    }

    private static void putIfPresent(Properties props, String key, String value) {
        if (Objects.nonNull(value) && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private static String trim(String value) {
        return Objects.nonNull(value) ? value.trim() : null;
    }

    /** Reads an INTEGER/SMALLINT column that may be SQL NULL, returning {@code null} rather than 0. */
    private static Integer readNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String require(String value, String name) {
        if (Objects.isNull(value) || value.isBlank()) {
            throw new IllegalStateException("Missing required query in hcl-queries.yml: " + name);
        }
        return value;
    }
}
