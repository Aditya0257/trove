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
}
