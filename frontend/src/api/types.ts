export interface TopicGroup {
  name: string;
  topics: string[];
}

export interface PubSubEnvironment {
  name: string;
  projectId: string;
  topicGroups: TopicGroup[];
}

export interface AppConfig {
  defaultProjectId: string;
  emulator: boolean;
  emulatorHost: string;
  restricted: boolean;
  allowedTopics: string[];
  topicGroups: TopicGroup[];
  environments: PubSubEnvironment[];
}

export interface AuthStatus {
  authenticated: boolean;
  emulator: boolean;
  loginAvailable: boolean;
  loginInProgress: boolean;
  lastError: string | null;
}

export interface TopicInfo {
  id: string;
  name: string;
}

export interface SubscriptionInfo {
  id: string;
  name: string;
  topic: string;
  topicId: string;
  ackDeadlineSeconds: number;
  retainAckedMessages: boolean;
  messageRetentionDuration: string;
  hasPush: boolean;
  pushEndpoint: string;
}

export interface SubscriptionCounts {
  subscriptionId: string;
  total: number;
  ack: number;
  nonAck: number;
  available: boolean;
  note: string | null;
}

export interface TopicCounts {
  topicId: string;
  total: number;
  ack: number;
  nonAck: number;
  available: boolean;
  note: string | null;
  subscriptions: SubscriptionCounts[];
}

export interface MessageView {
  messageId: string;
  ackId: string;
  data: string;
  attributes: Record<string, string>;
  orderingKey: string;
  publishTime: string;
  deliveryAttempt: number;
  source: string;
}

export interface PublishMessageRequest {
  data: string;
  attributes: Record<string, string>;
  orderingKey: string | null;
}

// ---- Cross-environment topic transfer ----
export interface TransferRequest {
  sourceEnv: string;
  targetEnv: string;
  sourceTopicId: string;
  targetTopicId: string;
  sourceSubscriptionId?: string;
  max?: number;
  dryRun?: boolean;
}

export interface TransferResult {
  sourceEnv: string;
  targetEnv: string;
  sourceProject: string;
  targetProject: string;
  sourceTopicId: string;
  targetTopicId: string;
  sourceSubscriptionId: string;
  read: number;
  published: number;
  failed: number;
  dryRun: boolean;
  errors: string[];
  sampleIds: string[];
  messages: MessageView[];
}

export interface BulkPublishResult {
  total: number;
  published: number;
  failed: number;
  errors: string[];
}

// ---- Mongo ----
export interface MongoEnvironment {
  name: string;
  databases: string[];
}

export interface CleanupGroupConfig {
  label: string;
  database: string;
  collections: string[];
}

export interface MongoConfig {
  environments: MongoEnvironment[];
  productCleanup: CleanupGroupConfig[];
}

export interface MongoDocument {
  id: string;
  json: string;
}

export interface MongoDocumentResponse {
  found: boolean;
  count: number;
  documents: MongoDocument[];
}

export interface CleanupScanResponse {
  env: string;
  productId: string;
  total: number;
  groups: {
    label: string;
    database: string;
    collections: { name: string; count: number }[];
  }[];
}

export interface CleanupDeleteResponse {
  env: string;
  productId: string;
  totalDeleted: number;
  results: { database: string; collection: string; deleted: number }[];
}

// ---- HCL Data Explorer ----
export interface HclStatus {
  env: string;
  up: boolean;
  host: string;
  error?: string;
}

export type HclDoc = Record<string, unknown>;

export interface HclSku {
  sku: HclDoc;
  price: HclDoc;
  item: HclDoc;
}

export interface HclVariant {
  variant: HclDoc;
  enrichedProduct: HclDoc | null;
  enrichedPublishReady: boolean;
  skus: HclSku[];
}

export interface HclProductResponse {
  env: string;
  productId: string;
  catEntryId?: number;
  found: boolean;
  reason?: string;
  product?: HclDoc;
  rating?: HclDoc | null;
  variants?: HclVariant[];
  counts?: Record<string, number>;
  collections?: Record<string, string>;
}

// ---- CT Data Explorer ----
export interface CtStatus {
  env: string;
  connected: boolean;
  projectKey?: string;
  projectName?: string;
  scope?: string;
  error?: string;
}

export type CtDoc = Record<string, unknown>;

export interface CtPrice {
  country: string | null;
  currency: string | null;
  centAmount: number;
  fractionDigits: number;
  amount: string;
}

export interface CtCategory {
  id: string;
  name?: string | null;
  key?: string | null;
  externalId?: string | null;
}

export interface CtSku {
  sku: string | null;
  attributes: Record<string, unknown>;
  categories: string[];
  prices: CtPrice[];
  raw: CtDoc;
}

export interface CtVariant {
  id: string | null;
  variantId: string | null;
  version: number;
  published: boolean;
  name: string | null;
  skus: CtSku[];
  raw: CtDoc;
}

export interface CtProduct {
  id: string | null;
  key: string | null;
  version: number;
  published: boolean;
  name: string | null;
  description: string | null;
  categories: string[];
  images: string[];
  attributes: Record<string, unknown>;
  raw: CtDoc;
}

export interface CtProductResponse {
  env: string;
  productId: string;
  projectKey?: string;
  found: boolean;
  reason?: string;
  product?: CtProduct;
  variants?: CtVariant[];
  categoriesById?: Record<string, CtCategory>;
  counts?: Record<string, number>;
}

// ---- CT Clean Up ----
export interface CtCleanupProduct {
  id: string;
  key: string;
  version: number;
  published: boolean;
}

export interface CtCleanupVariant {
  id: string;
  variantId: string;
  version: number;
  published: boolean;
  skuCount: number;
}

export interface CtCleanupScanResponse {
  env: string;
  productId: string;
  found: boolean;
  reason?: string;
  product?: CtCleanupProduct;
  variants?: CtCleanupVariant[];
  counts?: Record<string, number>;
}

export interface CtCleanupResult {
  type: string;
  id: string;
  label: string;
  deleted: boolean;
  note?: string;
  error?: string;
}

export interface CtCleanupDeleteResponse {
  env: string;
  productId: string;
  totalDeleted: number;
  results: CtCleanupResult[];
  reason?: string;
}

// ---- Categories ----
export interface CategoryHclProduct {
  catEntryId: number;
  partNumber: string | null;
  name: string | null;
  published: string | null;
}

export interface CategoryHclResponse {
  env: string;
  categoryId: string;
  found: boolean;
  reason?: string;
  catGroupId?: number;
  identifier?: string | null;
  count: number;
  productsShown?: number;
  products?: CategoryHclProduct[];
}

export interface CategoryCatalogCounts {
  activeProducts: number;
  activeVariants: number;
  totalActive: number;
  total: number;
}

export interface CategoryCatalogSummary {
  id: string;
  hclCategoryId: string | null;
  name: string | null;
  seoUrl: string | null;
  type: string | null;
  subType: string | null;
  status: string | null;
}

export interface CategoryCatalogBlock {
  database: string;
  available: boolean;
  error?: string;
  categoryFound?: boolean;
  category?: CategoryCatalogSummary;
  categoryJson?: string;
  counts?: CategoryCatalogCounts;
  associations?: MongoDocument[];
  associationsShown?: number;
}

export interface CategoryCatalogResponse {
  env: string;
  categoryId: string;
  config: CategoryCatalogBlock;
  runtime: CategoryCatalogBlock;
}

export interface CategoryConstructorResult {
  id: string | null;
  value: string | null;
}

export interface CategoryConstructorResponse {
  env: string;
  categoryId: string;
  configured: boolean;
  reason?: string;
  ok?: boolean;
  statusCode?: number;
  apiUrl?: string;
  requestUrl?: string;
  groupId?: string;
  resolvedFromCategoryId?: string;
  categoryName?: string | null;
  count?: number;
  resultsShown?: number;
  results?: CategoryConstructorResult[];
}

// ---- Categories: cross-source reconciliation ----
export interface CategoryReconcileSourceBlock {
  available: boolean;
  error?: string;
  reason?: string;
  count?: number;
  distinct?: number;
  categoryFound?: boolean;
  categoryId?: string;
  categoryName?: string | null;
  catGroupId?: number;
  groupId?: string;
  database?: string;
}

export interface CategoryReconcileInventoryBlock {
  available: boolean;
  error?: string;
  database?: string;
  skuCount?: number;
  inStockSkuCount?: number;
  inStockProductCount?: number;
}

export interface CategoryReconcileRow {
  id: string;
  hcl: boolean;
  catalog: boolean;
  constructor: boolean;
  inStock: boolean;
}

export interface CategoryReconcileSummary {
  hclAvailable: boolean;
  catalogAvailable: boolean;
  constructorAvailable: boolean;
  inventoryAvailable: boolean;
  unionCount: number;
  commonAllCount: number;
  commonAll: string[];
  missingFromHclCount: number;
  missingFromHcl: string[];
  missingFromCatalogCount: number;
  missingFromCatalog: string[];
  missingFromConstructorCount: number;
  missingFromConstructor: string[];
  inStockNotInConstructorCount: number;
  inStockNotInConstructor: string[];
  constructorNotInStockCount: number;
  constructorNotInStock: string[];
  matrixShown: number;
  matrix: CategoryReconcileRow[];
}

export interface CategoryReconcileResponse {
  env: string;
  categoryId: string;
  hcl: CategoryReconcileSourceBlock;
  catalog: CategoryReconcileSourceBlock;
  constructor: CategoryReconcileSourceBlock;
  inventory: CategoryReconcileInventoryBlock;
  summary: CategoryReconcileSummary;
}

// ---- Automation (read-only scenario runner) ----
export interface AutomationGroup {
  id: string;
  label: string;
  description: string;
}

export interface AutomationScenario {
  id: string;
  group: string;
  groupLabel: string;
  category: string;
  title: string;
  priority: string;
  feasibility: "READONLY" | "NOT_APPLICABLE";
  requiresProductId: boolean;
  note: string;
  /** Verbatim scenario spec from the test-plan workbook (Scenario -> Expected result). */
  spec: string;
}

export interface AutomationCatalog {
  groups: AutomationGroup[];
  scenarios: AutomationScenario[];
}

export interface AutomationFieldDiff {
  field: string;
  docType: string;
  expected: string | null;
  actual: string | null;
  verdict: "MATCH" | "DIFFERS" | "MISSING" | "EXTRA" | "INFO" | "GAP" | "XFORM" | string;
}

export type CheckStatus = "PASS" | "FAIL" | "SKIP" | "NA" | "ERROR";

export interface AutomationCheckResult {
  scenarioId: string;
  status: CheckStatus;
  checked: number;
  failed: number;
  message: string;
  expected: string | null;
  actual: string | null;
  sampleIds: string[];
  diffs: AutomationFieldDiff[];
}

/** The scenario definition as embedded in a run result (subset of the catalog entry). */
export interface AutomationScenarioDef {
  id: string;
  group: string;
  category: string;
  title: string;
  priority: string;
  feasibility: "READONLY" | "NOT_APPLICABLE";
  note: string;
  /** Verbatim scenario spec from the test-plan workbook (Scenario -> Expected result). */
  spec: string;
}

export interface AutomationScenarioResult {
  scenario: AutomationScenarioDef;
  result: AutomationCheckResult;
}

export interface AutomationRunSummary {
  env: string;
  productId: string | null;
  sampleSize: number;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
  total: number;
  passed: number;
  failed: number;
  skipped: number;
  notApplicable: number;
  errored: number;
  results: AutomationScenarioResult[];
}

export interface AutomationRunRequest {
  env: string;
  group?: string;
  scenarioIds?: string[];
  all?: boolean;
  productId?: string;
  sampleSize?: number;
}

export interface AutomationAiStatus {
  configuredProvider: string;
  effectiveProvider: string;
  llmConfigured: boolean;
  model: string | null;
}

export interface AutomationAiResponse {
  provider: string;
  configured: boolean;
  analysis: string;
}

// ---- Automation: raw HCL <-> Catalog single-document compare ----
export type HclCompareType = "PRODUCT" | "VARIANT" | "SKU" | "PRICE" | "ENRICHED";

export interface HclRawCompareRequest {
  env: string;
  productId: string;
  type: HclCompareType;
}

export interface HclRawCompareResponse {
  found: boolean;
  message: string;
  env: string;
  productId: string;
  type: string;
  docType: string | null;
  docId: string | null;
  collection: string | null;
  status: CheckStatus;
  checked: number;
  failed: number;
  diffs: AutomationFieldDiff[];
}

// ---- Scenario Runner (Perf-only injection + verify) ----
export type ScenarioKind = "STREAMING" | "BATCH";

export interface ScenarioCategoryMeta {
  id: string;
  label: string;
}

export interface ScenarioSpec {
  id: string;
  category: string;
  categoryLabel: string;
  shortName: string;
  kind: ScenarioKind;
  processor: string;
  description: string;
  enabled: boolean;
  target: string;
  topicId: string | null;
  gcsBucket: string | null;
  gcsObjectPrefix: string | null;
  defaultFileName: string | null;
  githubRepo: string | null;
  workflowFile: string | null;
  verifyMode: string;
  verifyTarget: string;
  /** Full-load reconcile job: a COMPLETE feed upload is required (bundled sample is format-only). */
  requiresFullFeed: boolean;
  /** Whether opt-in pre-injection cleanup of the minimal golden data is supported. */
  supportsCleanup: boolean;
}

export interface ScenarioGithubStatus {
  configured: boolean;
  ref: string;
}

export interface ScenarioCatalog {
  categories: ScenarioCategoryMeta[];
  scenarios: ScenarioSpec[];
  perfOnly: boolean;
  perfEnv: string;
  projectId: string;
  streamWaitSeconds: number;
  batchTimeoutSeconds: number;
  github: ScenarioGithubStatus;
}

export interface ScenarioSample {
  id: string;
  kind: ScenarioKind;
  fileName: string | null;
  content: string;
}

export type PhaseStatus = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "SKIPPED";

export interface ScenarioRunPhase {
  name: string;
  status: PhaseStatus;
  startedAt: string | null;
  finishedAt: string | null;
  detail: string | null;
}

export interface ScenarioRunState {
  runId: string;
  scenarioId: string;
  shortName: string;
  category: string;
  kind: ScenarioKind;
  env: string;
  startedAt: string;
  finishedAt: string | null;
  /** RUNNING | PASS | FAIL | ERROR */
  status: string;
  message: string;
  done: boolean;
  injection: Record<string, unknown>;
  phases: ScenarioRunPhase[];
  verify: AutomationRunSummary | null;
}

export interface ScenarioRunRequest {
  env: string;
  scenarioId: string;
  payloadOverride?: string;
  version?: string;
  fileName?: string;
  fileBase64?: string;
  /** Opt-in: delete the minimal golden data (Perf) before injecting so verify proves this run. */
  cleanup?: boolean;
}

// ---- Schemas (Bulk Post) ----
export interface SchemaField {
  name: string;
  types: string[];
  nullable: boolean;
  required: boolean;
  enum?: string[];
}

export interface SchemaDescriptor {
  id: string;
  title: string;
  coercible: boolean;
  additionalProperties: boolean;
  required: string[];
  fields: SchemaField[];
}
