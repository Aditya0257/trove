/** Shapes returned by the Trove API (see docs/API.md). */

export interface AuthResponse {
  token: string | null;         // null when a second factor is required, or account is pending
  userId: string;
  email: string;
  displayName: string;
  twoFactorRequired?: boolean;  // true = password ok, now supply the authenticator code
  admin?: boolean;              // true = the configured admin (can approve sign-ups)
  status?: string;              // 'active' | 'pending' | 'rejected'
}

export interface PendingUser {
  id: string;
  email: string;
  displayName: string;
  requestedAt: string | null;
}

/** The signed-in user's own profile + security summary (GET /api/account/me). */
export interface AccountResponse {
  email: string;
  displayName: string;
  admin: boolean;
  twoFactorEnabled: boolean;
  avatarUrl: string | null;     // short-lived presigned URL, or null when no photo
  pendingEmail: string | null;  // a new email awaiting OTP confirmation, if any
  createdAt: string;
}

/** One account in the admin's user list (for the delete-account picker). */
export interface AdminUser {
  id: string;
  email: string;
  displayName: string;
  status: string;
  admin: boolean;
  createdAt: string;
}

export interface LineItem {
  description: string | null;
  quantity: number | null;
  unitPrice: number | null;
  amount: number | null;
}

export interface DocumentResponse {
  id: string;
  spaceId: string;
  uploadedBy: string;
  storageKey: string;
  sidecarKey: string;
  fileHash: string;
  mimeType: string;
  sizeBytes: number;
  originalFilename: string | null;
  category: string | null;
  merchant: string | null;
  docDate: string | null;
  amount: number | null;
  currency: string | null;
  dueDate: string | null;
  rawText: string | null;
  extra: Record<string, unknown>;
  extractionConfidence: number | null;
  vital: boolean;
  encrypted: boolean;
  status: 'needs_review' | 'confirmed' | 'deleted';
  reviewedBy: string | null;
  reviewedAt: string | null;
  deletedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  fileUrl: string | null;
  lineItems: LineItem[];
}

export interface Category {
  code: string;
  label: string;
  global: boolean;
}

export interface ConfirmRequest {
  category?: string;
  merchant?: string;
  docDate?: string;
  amount?: number;
  currency?: string;
  dueDate?: string;
  vital?: boolean;
  /** Extra fields merged into the document (e.g. Mail metadata: account/subject/bundle). */
  extra?: Record<string, unknown>;
}

export interface SpaceSummary {
  id: string;
  name: string;
  description: string | null;
  kind: 'personal' | 'shared';
  createdBy: string;
  createdAt: string;
}

export interface Member {
  userId: string;
  displayName: string | null;
  email: string | null;
  role: 'owner' | 'member' | 'viewer';
  status: 'active' | 'pending' | 'declined';
  selfRequested: boolean;   // pending + came via a join link (owner approves), not an invite
  joinedAt: string;
}

/** An outstanding invitation to a space, shown to the invited user. */
export interface Invitation {
  spaceId: string;
  spaceName: string;
  spaceKind: 'personal' | 'shared';
  role: string;
  invitedByName: string | null;
  invitedByEmail: string | null;
}

export interface IngestAddress {
  token: string;
  address: string;
}

export type ReminderRecurrence = 'none' | 'weekly' | 'monthly' | 'quarterly' | 'yearly';

export interface ReminderResponse {
  id: string;
  documentId: string | null;
  spaceId: string;
  type: 'due' | 'renewal' | 'warranty_expiry';
  title: string | null;
  remindOn: string;
  recurrence: ReminderRecurrence;
  status: 'pending' | 'sent' | 'dismissed' | 'done';
  completedAt: string | null;
  createdAt: string;
  documentFilename: string | null;  // linked file name, so the list needs no doc fetch
}

/** One filed email thread (bundle): latest metadata plus its screenshots. */
export interface MailBundleView {
  bundleId: string;
  account: string;
  address: string;
  topic: string;
  subject: string;
  date: string;
  count: number;
  docs: DocumentResponse[];
}

/** A page of email threads plus the add-form autocomplete facets and the total thread count. */
export interface MailPage {
  bundles: MailBundleView[];
  total: number;
  accounts: string[];
  topics: string[];
  addresses: string[];
}

export interface CategorySpend {
  category: string;
  label: string;
  total: number;
  count: number;
}

export interface MonthlySpend {
  period: string;
  total: number;
  count: number;
}

export interface SpendSummary {
  from: string;
  to: string;
  currency: string;
  ratesAsOf: string | null;
  total: number;
  count: number;
  byCategory: CategorySpend[];
}

/** One upcoming (or just-passed) thing to act on - a bill due, a renewal, a warranty end. */
export interface ExpiringItem {
  documentId: string;
  title: string;
  category: string | null;
  kind: 'due' | 'renewal' | 'warranty';
  date: string;
  daysLeft: number; // negative = already overdue
  amount: number | null;
  currency: string | null;
}

/** A merchant+category that recurs on a regular cadence (a subscription/recurring bill). */
export interface RecurringGroup {
  merchant: string | null;
  category: string | null;
  categoryLabel: string | null;
  occurrences: number;
  cadence: 'weekly' | 'monthly' | 'quarterly' | 'yearly';
  averageAmount: number | null;
  currency: string | null;
  lastSeen: string;
  nextExpected: string | null;
}

/** One Google Drive linked to a space (a space may pool several). */
export interface DriveConnectionView {
  id: string;
  googleEmail: string | null;        // which Google account this Drive is
  googleAccountName: string | null;  // its display name, when Drive returns one
  connectedByName: string | null;    // the Trove member who linked it
  active: boolean;                   // the current write target (rotate mode)
  status: string;                    // 'active' | 'full' | 'error'
  connectedAt: string | null;
  lastSyncAt: string | null;
  storageLimitBytes: number | null;  // total Drive quota (null = unlimited)
  storageUsageBytes: number | null;  // bytes used across the whole account
  troveBytes: number | null;         // of that, how much Trove put there
}

export interface DriveStatus {
  connected: boolean;
  mode: string;                      // 'rotate' (aggregate) | 'mirror' (redundant copies)
  connections: DriveConnectionView[];
}

export interface ChatCitation {
  documentId: string;
  index: number;
  title: string;
  category: string | null;
  docDate: string | null;
  amount: number | null;
  currency: string | null;
  snippet: string;
}

export interface ChatAnswer {
  answer: string;
  aiUsed: boolean;         // false = retrieval-only (AI summary paused/off)
  sources: ChatCitation[];
}

export interface IntegrityIssue {
  documentId: string;
  title: string;
  severity: 'critical' | 'warning' | 'info';
  problem: string;
}

export interface StorageIntegrity {
  r2Objects: number;
  indexedKeys: number;
  orphanObjects: number;
  rebuildableOrphans: number;
  mirrorEnabled: boolean;
  mirrorObjects: number;
}

export interface IntegrityReport {
  spaceId: string;
  checkedAt: string;
  documents: number;
  primaryOk: number;
  sidecarOk: number;
  mirrorOk: number | null;   // null = mirror not configured
  driveOk: number;
  criticalCount: number;
  issues: IntegrityIssue[];
  storage: StorageIntegrity;
}

export interface BackupRun {
  kind: string;
  status: 'running' | 'success' | 'failed';
  location: string | null;
  detail: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface SearchResult {
  interpreted: Record<string, unknown>;
  count: number;
  results: DocumentResponse[];
}

/** Today's AI consumption: the shared + per-user neuron limits, the app-wide total, and yours. */
export interface AiUsage {
  limitNeurons: number;
  perUserLimitNeurons: number;
  global: { neurons: number; tokens: number };
  user: { neurons: number; tokens: number };
}

/**
 * Free-tier usage across every backing service, for the Developer gauge. Two daily
 * pools (AI, email) reset at `dailyResetAt` (the next 00:00 UTC instant); the storage
 * meters are running totals with no reset.
 */
export interface UsageOverview {
  dailyResetAt: string;
  ai: {
    limitNeurons: number;
    perUserLimitNeurons: number;
    globalNeurons: number;
    globalTokens: number;
    userNeurons: number;
    userTokens: number;
  };
  email: { dailyLimit: number; sentToday: number };
  storage: { usedBytes: number; limitBytes: number };
  database: { usedBytes: number; limitBytes: number };
  mirror: { enabled: boolean; usedBytes: number; limitBytes: number };
}

