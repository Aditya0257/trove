/** Shapes returned by the Trove API (see docs/API.md). */

export interface AuthResponse {
  token: string;
  userId: string;
  email: string;
  displayName: string;
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

export interface ReminderResponse {
  id: string;
  documentId: string | null;
  spaceId: string;
  type: 'due' | 'renewal' | 'warranty_expiry';
  remindOn: string;
  status: 'pending' | 'sent' | 'dismissed';
  createdAt: string;
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

