import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { API_BASE } from './config';

/** Display order for categories - by everyday usefulness, financial kinds clustered,
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
  BackupRun,
  Category,
  CategorySpend,
  ChatAnswer,
  ConfirmRequest,
  DocumentResponse,
  DriveStatus,
  IngestAddress,
  IntegrityReport,
  Invitation,
  Member,
  MonthlySpend,
  ReminderResponse,
  SearchResult,
  SpaceSummary,
  SpendSummary,
} from './models';

/**
 * Wrapper over the Trove REST API (see docs/API.md). `spaceId` is optional - when
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
  /**
   * One page of documents plus the total match count (from the X-Total-Count header), so the
   * list pages server-side instead of pulling every row. size = 0 means "all" (browser-find).
   */
  listDocumentsPage(spaceId: string | undefined, category: string | undefined, page: number, size: number) {
    return this.http
      .get<DocumentResponse[]>(`${API_BASE}/api/documents${this.qs({ spaceId, category, page, size })}`,
        { observe: 'response' })
      .pipe(map((resp) => ({
        items: resp.body ?? [],
        total: Number(resp.headers.get('X-Total-Count') ?? (resp.body?.length ?? 0)),
      })));
  }
  getDocument(id: string) {
    return this.http.get<DocumentResponse>(`${API_BASE}/api/documents/${id}`);
  }
  uploadDocument(file: File, vital: boolean, spaceId?: string, extract = true) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<DocumentResponse>(
      `${API_BASE}/api/documents${this.qs({ spaceId, vital, extract })}`,
      form
    );
  }
  confirmDocument(id: string, body: ConfirmRequest) {
    return this.http.post<DocumentResponse>(`${API_BASE}/api/documents/${id}/confirm`, body);
  }
  deleteDocument(id: string) {
    return this.http.delete<void>(`${API_BASE}/api/documents/${id}`);
  }
  listTrash(spaceId?: string) {
    return this.http.get<DocumentResponse[]>(`${API_BASE}/api/documents/trash${this.qs({ spaceId })}`);
  }
  restoreDocument(id: string) {
    return this.http.post<void>(`${API_BASE}/api/documents/${id}/restore`, {});
  }
  purgeDocument(id: string) {
    return this.http.delete<void>(`${API_BASE}/api/documents/${id}/purge`);
  }

  aiUsage() {
    return this.http.get<AiUsage>(`${API_BASE}/api/ai-usage`);
  }
  fileUrl(doc: DocumentResponse): string | null {
    if (!doc.fileUrl) return null;
    return doc.fileUrl.startsWith('http') ? doc.fileUrl : `${API_BASE}${doc.fileUrl}`;
  }

  /** Fetches file bytes via the API (auth header attached) - needed for vital docs,
   *  which are served from /content and can't be opened as a plain link. */
  getContent(id: string) {
    return this.http.get(`${API_BASE}/api/documents/${id}/content`, { responseType: 'blob' });
  }

  // --- search ---
  search(q: string, spaceId?: string) {
    return this.http.get<SearchResult>(`${API_BASE}/api/search${this.qs({ q, spaceId })}`);
  }

  // --- ask (RAG) ---
  chatAsk(question: string, spaceId?: string) {
    return this.http.post<ChatAnswer>(`${API_BASE}/api/chat/ask${this.qs({ spaceId })}`, { question });
  }
  chatReindex(spaceId?: string) {
    return this.http.post<{ indexed: number }>(`${API_BASE}/api/chat/reindex${this.qs({ spaceId })}`, {});
  }

  // --- spend ---
  spendSummary(spaceId?: string, currency = 'INR') {
    return this.http.get<SpendSummary>(`${API_BASE}/api/spend/summary${this.qs({ spaceId, currency })}`);
  }
  spendByMonth(spaceId?: string, currency = 'INR', granularity = 'month') {
    return this.http.get<MonthlySpend[]>(`${API_BASE}/api/spend/by-month${this.qs({ spaceId, currency, granularity })}`);
  }

  // --- anomalies ---
  listAnomalies(spaceId?: string) {
    return this.http.get<DocumentResponse[]>(`${API_BASE}/api/anomalies${this.qs({ spaceId })}`);
  }

  // --- reminders ---
  listReminders(spaceId?: string, status?: string) {
    return this.http.get<ReminderResponse[]>(`${API_BASE}/api/reminders${this.qs({ spaceId, status })}`);
  }
  createReminder(
    body: { type: string; remindOn: string; documentId?: string; title?: string; recurrence?: string },
    spaceId?: string,
  ) {
    return this.http.post<ReminderResponse>(`${API_BASE}/api/reminders${this.qs({ spaceId })}`, body);
  }
  updateReminder(
    id: string,
    body: { type: string; remindOn: string; title?: string; recurrence?: string; documentId?: string },
  ) {
    return this.http.patch<ReminderResponse>(`${API_BASE}/api/reminders/${id}`, body);
  }
  snoozeReminder(id: string, days: number) {
    return this.http.post<ReminderResponse>(`${API_BASE}/api/reminders/${id}/snooze${this.qs({ days: String(days) })}`, {});
  }
  doneReminder(id: string) {
    return this.http.post<ReminderResponse>(`${API_BASE}/api/reminders/${id}/done`, {});
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
  approveMember(spaceId: string, userId: string) {
    return this.http.post<Member>(`${API_BASE}/api/spaces/${spaceId}/members/${userId}/approve`, {});
  }

  // --- space join link ---
  spaceJoinLink(spaceId: string) {
    return this.http.get<{ token: string; url: string }>(`${API_BASE}/api/spaces/${spaceId}/join-link`);
  }
  rotateSpaceJoinLink(spaceId: string) {
    return this.http.post<{ token: string; url: string }>(`${API_BASE}/api/spaces/${spaceId}/join-link/rotate`, {});
  }
  revokeSpaceJoinLink(spaceId: string) {
    return this.http.delete<void>(`${API_BASE}/api/spaces/${spaceId}/join-link`);
  }
  requestJoinSpace(token: string) {
    return this.http.post<{ spaceId: string; spaceName: string }>(
      `${API_BASE}/api/spaces/join${this.qs({ token })}`, {});
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
  driveSetMode(spaceId: string, mode: string) {
    return this.http.put<{ mode: string }>(
      `${API_BASE}/api/integrations/google-drive/mode${this.qs({ spaceId, mode })}`,
      {}
    );
  }
  driveActivate(spaceId: string, connectionId: string) {
    return this.http.post<void>(
      `${API_BASE}/api/integrations/google-drive/connections/${connectionId}/activate${this.qs({ spaceId })}`,
      {}
    );
  }
  driveDisconnect(spaceId: string, connectionId: string) {
    return this.http.delete<void>(
      `${API_BASE}/api/integrations/google-drive/connections/${connectionId}${this.qs({ spaceId })}`
    );
  }

  // --- backup integrity ---
  integrityReport(spaceId?: string) {
    return this.http.get<IntegrityReport>(`${API_BASE}/api/integrity/report${this.qs({ spaceId })}`);
  }
  integrityHistory() {
    return this.http.get<BackupRun[]>(`${API_BASE}/api/integrity/history`);
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
