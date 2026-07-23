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
  status: 'needs_review' | 'confirmed';
  reviewedBy: string | null;
  reviewedAt: string | null;
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
  total: number;
  count: number;
  byCategory: CategorySpend[];
}

export interface DriveStatus {
  connected: boolean;
  connectedAt: string | null;
  lastSyncAt: string | null;
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

