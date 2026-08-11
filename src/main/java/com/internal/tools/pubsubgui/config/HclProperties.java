package com.internal.tools.pubsubgui.config;

import com.internal.tools.pubsubgui.hcl.config.HclConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-environment HCL Commerce DB2 configuration used by the HCL Data Explorer. Each environment
 * (Dev / QA / Perf) carries its own DB2 connection; the shared migration literals and collection
 * names match the tb-catalog-data-processor HCL migration so the built documents are faithful.
 *
 * <p>Assembles an {@link HclConfig} per environment for reuse of the copied reader/mappers.
 */
@Component
@ConfigurationProperties(prefix = "hcl")
public class HclProperties {

    private static final Logger log = LoggerFactory.getLogger(HclProperties.class);

    /** Ordered environments surfaced in the UI, each with its own DB2 connection. */
    private List<Environment> environments = new ArrayList<>();

    /** Shared migration literals (same across environments). */
    private Migration migration = new Migration();

    /** Destination collection names (display only — the tool never writes). */
    private Collections collections = new Collections();

    public List<Environment> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<Environment> environments) {
        this.environments = environments == null ? new ArrayList<>() : environments;
    }

    public Migration getMigration() {
        return migration;
    }

    public void setMigration(Migration migration) {
        this.migration = migration == null ? new Migration() : migration;
    }

    public Collections getCollections() {
        return collections;
    }

    public void setCollections(Collections collections) {
        this.collections = collections == null ? new Collections() : collections;
    }

    /**
     * The first environment (other than {@code target}) that has a non-blank DB2 user, whose
     * credentials can be borrowed when {@code target}'s are blank. Returns {@code null} if none.
     */
    private Environment credentialDonor(Environment target) {
        for (Environment env : environments) {
            if (env == target) {
                continue;
            }
            String user = env.getDb2().getUser();
            if (user != null && !user.isBlank()) {
                return env;
            }
        }
        return null;
    }

    /** Returns the environment by name, or {@code null} if unknown. */
    public Environment environment(String name) {
        if (name == null) {
            return null;
        }
        for (Environment env : environments) {
            if (env.getName().equalsIgnoreCase(name.trim())) {
                return env;
            }
        }
        return null;
    }

    /**
     * Builds an {@link HclConfig} (DB2 + migration literals) for the given environment. When the
     * environment's DB2 user is blank (e.g. QA, whose credentials are injected at deploy in the
     * reference project), the credentials fall back to the first configured environment that has
     * them — so the shared HCL DB2 stays usable with whatever HCL credentials are available.
     */
    public HclConfig toHclConfig(Environment env) {
        HclConfig config = new HclConfig();
        HclConfig.Db2 db2 = config.getDb2();
        db2.setUrl(env.getDb2().getUrl());

        String user = env.getDb2().getUser();
        String password = env.getDb2().getPassword();
        if (user == null || user.isBlank()) {
            Environment donor = credentialDonor(env);
            if (donor != null) {
                user = donor.getDb2().getUser();
                password = donor.getDb2().getPassword();
                log.info("hcl db2 | env={} has no credentials — using available HCL credentials from env={}",
                        env.getName(), donor.getName());
            }
        }
        db2.setUser(user);
        db2.setPassword(password);
        db2.setCurrentSchema(env.getDb2().getCurrentSchema());
        db2.setDriverClass(env.getDb2().getDriverClass());
        db2.setFetchSize(env.getDb2().getFetchSize());

        HclConfig.Migration mig = config.getMigration();
        mig.setBanner(migration.getBanner());
        mig.setAuditActor(migration.getAuditActor());
        mig.setColorCodeExcludedPrefixes(new ArrayList<>(migration.getColorCodeExcludedPrefixes()));
        mig.setListPriceType(migration.getListPriceType());
        mig.setSalePriceType(migration.getSalePriceType());
        return config;
    }

    /** A named environment with its DB2 connection. */
    public static class Environment {
        private String name = "";
        private Db2 db2 = new Db2();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public Db2 getDb2() {
            return db2;
        }

        public void setDb2(Db2 db2) {
            this.db2 = db2 == null ? new Db2() : db2;
        }
    }

    /** HCL Commerce DB2 (WCS schema) connection for one environment. */
    public static class Db2 {
        private String url = "";
        private String user = "";
        private String password = "";
        private String currentSchema = "WCS";
        private String driverClass = "com.ibm.db2.jcc.DB2Driver";
        private int fetchSize = 1000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url.trim();
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user == null ? "" : user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }

        public String getCurrentSchema() {
            return currentSchema;
        }

        public void setCurrentSchema(String currentSchema) {
            this.currentSchema = currentSchema == null ? "" : currentSchema.trim();
        }

        public String getDriverClass() {
            return driverClass;
        }

        public void setDriverClass(String driverClass) {
            this.driverClass = (driverClass == null || driverClass.isBlank())
                    ? "com.ibm.db2.jcc.DB2Driver" : driverClass.trim();
        }

        public int getFetchSize() {
            return fetchSize;
        }

        public void setFetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
        }

        /** Host:port/database extracted from the JDBC URL, for the status bulb. */
        public String host() {
            if (url == null || url.isBlank()) {
                return "";
            }
            int idx = url.indexOf("://");
            return idx >= 0 ? url.substring(idx + 3) : url;
        }
    }

    /** Shared migration literals mirrored from tb-catalog-data-processor. */
    public static class Migration {
        private String banner = "TMW";
        private String auditActor = "HCLDataMigrationProcessor";
        private List<String> colorCodeExcludedPrefixes = new ArrayList<>();
        private String listPriceType = "TMWCASList";
        private String salePriceType = "TMWSalePrice";

        public String getBanner() {
            return banner;
        }

        public void setBanner(String banner) {
            this.banner = banner;
        }

        public String getAuditActor() {
            return auditActor;
        }

        public void setAuditActor(String auditActor) {
            this.auditActor = auditActor;
        }

        public List<String> getColorCodeExcludedPrefixes() {
            return colorCodeExcludedPrefixes;
        }

        public void setColorCodeExcludedPrefixes(List<String> colorCodeExcludedPrefixes) {
            this.colorCodeExcludedPrefixes =
                    colorCodeExcludedPrefixes == null ? new ArrayList<>() : colorCodeExcludedPrefixes;
        }

        public String getListPriceType() {
            return listPriceType;
        }

        public void setListPriceType(String listPriceType) {
            this.listPriceType = listPriceType;
        }

        public String getSalePriceType() {
            return salePriceType;
        }

        public void setSalePriceType(String salePriceType) {
            this.salePriceType = salePriceType;
        }
    }

    /** Destination collection names, shown as "would write to &lt;Collection&gt;" labels. */
    public static class Collections {
        private String product = "Product";
        private String variant = "Variant";
        private String sku = "SKU";
        private String price = "Price";
        private String rating = "Rating";
        private String enrichedProduct = "EnrichedProduct";
        private String item = "Item";

        public String getProduct() {
            return product;
        }

        public void setProduct(String product) {
            this.product = product;
        }

        public String getVariant() {
            return variant;
        }

        public void setVariant(String variant) {
            this.variant = variant;
        }

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public String getPrice() {
            return price;
        }

        public void setPrice(String price) {
            this.price = price;
        }

        public String getRating() {
            return rating;
        }

        public void setRating(String rating) {
            this.rating = rating;
        }

        public String getEnrichedProduct() {
            return enrichedProduct;
        }

        public void setEnrichedProduct(String enrichedProduct) {
            this.enrichedProduct = enrichedProduct;
        }

        public String getItem() {
            return item;
        }

        public void setItem(String item) {
            this.item = item;
        }
    }
}
