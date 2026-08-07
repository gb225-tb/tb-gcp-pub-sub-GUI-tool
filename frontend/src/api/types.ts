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
