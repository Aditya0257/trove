import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE } from './config';
import { Category, ConfirmRequest, DocumentResponse } from './models';

/** Thin wrapper over the document + category endpoints used by the web vertical. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  listCategories() {
    return this.http.get<Category[]>(`${API_BASE}/api/categories`);
  }

  listDocuments(category?: string) {
    const q = category ? `?category=${encodeURIComponent(category)}` : '';
    return this.http.get<DocumentResponse[]>(`${API_BASE}/api/documents${q}`);
  }

  getDocument(id: string) {
    return this.http.get<DocumentResponse>(`${API_BASE}/api/documents/${id}`);
  }

  uploadDocument(file: File, vital: boolean) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<DocumentResponse>(
      `${API_BASE}/api/documents?vital=${vital}`,
      form
    );
  }

  confirmDocument(id: string, body: ConfirmRequest) {
    return this.http.post<DocumentResponse>(`${API_BASE}/api/documents/${id}/confirm`, body);
  }

  /** Absolute URL for viewing a document's file (presigned, or the decrypt-stream path). */
  fileUrl(doc: DocumentResponse): string | null {
    if (!doc.fileUrl) {
      return null;
    }
    return doc.fileUrl.startsWith('http') ? doc.fileUrl : `${API_BASE}${doc.fileUrl}`;
  }
}
