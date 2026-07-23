import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { API_BASE } from './config';

/** Display order for categories — by everyday usefulness, financial kinds clustered,
 *  with the catch-all buckets pinned to the end. Codes not listed sort alphabetically
 *  just before "other". */
const CATEGORY_ORDER = [
  'uncategorized', 'shopping', 'food', 'electricity', 'water', 'gas', 'internet',
  'mobile', 'rent', 'subscription', 'travel', 'medical', 'insurance', 'tax', 'bank', 'other',
];
function categoryRank(code: string): number {
  const i = CATEGORY_ORDER.indexOf(code);
  if (code === 'other') return 1000;             // always last
  return i >= 0 ? i : 900;                        // unknown codes just before "other"
}
import {
  AiUsage,
  Category,
  CategorySpend,
  ConfirmRequest,
  DocumentResponse,
  DriveStatus,
  IngestAddress,
  Invitation,
  Member,
  MonthlySpend,
  ReminderResponse,
  SearchResult,
  SpaceSummary,
  SpendSummary,
} from './models';

/**
 * Wrapper over the Trove REST API (see docs/API.md). `spaceId` is optional — when
 * omitted the backend uses the caller's personal space.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  // --- documents ---
  listCategories(spaceId?: string) {
    return this.http.get<Category[]>(`${API_BASE}/api/categories${this.qs({ spaceId })}`).pipe(
      // Order by everyday usefulness (see CATEGORY_ORDER) rather than however the DB
      // returned them, so "Bank" sits with the financial kinds and "Other" stays last.
      map((cats) => [...cats].sort((a, b) => categoryRank(a.code) - categoryRank(b.code))),
    );
  }
  listDocuments(spaceId?: string, category?: string) {
    return this.http.get<DocumentResponse[]>(`${API_BASE}/api/documents${this.qs({ spaceId, category })}`);
  }
  getDocument(id: string) {
    return this.http.get<DocumentResponse>(`${API_BASE}/api/documents/${id}`);
  }
  uploadDocument(file: File, vital: boolean, spaceId?: string) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<DocumentResponse>(
      `${API_BASE}/api/documents${this.qs({ spaceId, vital })}`,
      form
    );
  }
  confirmDocument(id: string, body: ConfirmRequest) {
    return this.http.post<DocumentResponse>(`${API_BASE}/api/documents/${id}/confirm`, body);
  }
  deleteDocument(id: string) {
    return this.http.delete<void>(`${API_BASE}/api/documents/${id}`);
  }

  aiUsage() {
    return this.http.get<AiUsage>(`${API_BASE}/api/ai-usage`);
  }
  fileUrl(doc: DocumentResponse): string | null {
    if (!doc.fileUrl) return null;
    return doc.fileUrl.startsWith('http') ? doc.fileUrl : `${API_BASE}${doc.fileUrl}`;
  }

  /** Fetches file bytes via the API (auth header attached) — needed for vital docs,
   *  which are served from /content and can't be opened as a plain link. */
  getContent(id: string) {
    return this.http.get(`${API_BASE}/api/documents/${id}/content`, { responseType: 'blob' });
  }

  // --- search ---
  search(q: string, spaceId?: string) {
    return this.http.get<SearchResult>(`${API_BASE}/api/search${this.qs({ q, spaceId })}`);
  }

  // --- spend ---
  spendSummary(spaceId?: string) {
    return this.http.get<SpendSummary>(`${API_BASE}/api/spend/summary${this.qs({ spaceId })}`);
  }
  spendByMonth(spaceId?: string) {
    return this.http.get<MonthlySpend[]>(`${API_BASE}/api/spend/by-month${this.qs({ spaceId })}`);
  }

  // --- anomalies ---
  listAnomalies(spaceId?: string) {
    return this.http.get<DocumentResponse[]>(`${API_BASE}/api/anomalies${this.qs({ spaceId })}`);
  }

  // --- reminders ---
  listReminders(spaceId?: string, status?: string) {
    return this.http.get<ReminderResponse[]>(`${API_BASE}/api/reminders${this.qs({ spaceId, status })}`);
  }
  createReminder(body: { type: string; remindOn: string; documentId?: string }, spaceId?: string) {
    return this.http.post<ReminderResponse>(`${API_BASE}/api/reminders${this.qs({ spaceId })}`, body);
  }
  dismissReminder(id: string) {
    return this.http.post<ReminderResponse>(`${API_BASE}/api/reminders/${id}/dismiss`, {});
  }

  // --- spaces + members ---
  listSpaces() {
    return this.http.get<SpaceSummary[]>(`${API_BASE}/api/spaces`);
  }
  createSpace(name: string) {
    return this.http.post<SpaceSummary>(`${API_BASE}/api/spaces`, { name });
  }
  updateSpace(spaceId: string, name: string, description: string) {
    return this.http.put<SpaceSummary>(`${API_BASE}/api/spaces/${spaceId}`, { name, description });
  }
  deleteSpace(spaceId: string) {
    return this.http.delete<void>(`${API_BASE}/api/spaces/${spaceId}`);
  }
  listMembers(spaceId: string) {
    return this.http.get<Member[]>(`${API_BASE}/api/spaces/${spaceId}/members`);
  }
  addMember(spaceId: string, email: string, role: string) {
    return this.http.post<Member>(`${API_BASE}/api/spaces/${spaceId}/members`, { email, role });
  }
  removeMember(spaceId: string, userId: string) {
    return this.http.delete<void>(`${API_BASE}/api/spaces/${spaceId}/members/${userId}`);
  }

  // --- space invitations (accept/decline flow) ---
  listInvitations() {
    return this.http.get<Invitation[]>(`${API_BASE}/api/spaces/invitations`);
  }
  acceptInvite(spaceId: string) {
    return this.http.post<Member>(`${API_BASE}/api/spaces/${spaceId}/invitations/accept`, {});
  }
  declineInvite(spaceId: string) {
    return this.http.post<Member>(`${API_BASE}/api/spaces/${spaceId}/invitations/decline`, {});
  }

  // --- ingest address ---
  ingestAddress(spaceId: string) {
    return this.http.get<IngestAddress>(`${API_BASE}/api/spaces/${spaceId}/ingest-address`);
  }
  rotateIngestAddress(spaceId: string) {
    return this.http.post<IngestAddress>(`${API_BASE}/api/spaces/${spaceId}/ingest-address/rotate`, {});
  }

  // --- google drive ---
  driveStatus(spaceId: string) {
    return this.http.get<DriveStatus>(`${API_BASE}/api/integrations/google-drive/status${this.qs({ spaceId })}`);
  }
  driveAuthorizeUrl(spaceId: string) {
    return this.http.get<{ url: string }>(
      `${API_BASE}/api/integrations/google-drive/authorize-url${this.qs({ spaceId })}`
    );
  }
  driveSync(spaceId: string) {
    return this.http.post<{ synced: number; skipped: number }>(
      `${API_BASE}/api/integrations/google-drive/sync${this.qs({ spaceId })}`,
      {}
    );
  }

  // --- export ---
  exportZip(spaceId?: string) {
    return this.http.get(`${API_BASE}/api/export${this.qs({ spaceId })}`, { responseType: 'blob' });
  }

  /** Builds a ?a=b&c=d query string, skipping null/undefined/empty values. */
  private qs(params: Record<string, unknown>): string {
    const parts = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
    return parts.length ? `?${parts.join('&')}` : '';
  }
}
