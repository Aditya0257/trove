import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';

@Component({
  selector: 'app-upload',
  imports: [FormsModule],
  template: `
    <div class="card">
      <h1>Upload a document</h1>
      <p class="muted">Snap or pick a bill, receipt, policy or ID. Trove stores it and
        reads it; you'll confirm the details next.</p>
      <input type="file" (change)="onFile($event)" accept="image/*,application/pdf" />
      <label class="checkbox">
        <input type="checkbox" name="vital" [(ngModel)]="vital" />
        This is a vital/sensitive document (passport, ID, policy) — encrypt it at rest
      </label>
      @if (error()) { <p class="error">{{ error() }}</p> }
      <button (click)="upload()" [disabled]="!file() || loading()">
        {{ loading() ? 'Uploading…' : 'Upload' }}
      </button>
    </div>
  `,
})
export class Upload {
  private api = inject(ApiService);
  private router = inject(Router);
  private spaceCtx = inject(SpaceContext);

  file = signal<File | null>(null);
  vital = false;
  loading = signal(false);
  error = signal<string | null>(null);

  onFile(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
  }

  upload(): void {
    const f = this.file();
    if (!f) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api.uploadDocument(f, this.vital, this.spaceCtx.currentSpaceId()).subscribe({
      next: (doc) => this.router.navigate(['/documents', doc.id, 'review']),
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Upload failed');
        this.loading.set(false);
      },
    });
  }
}
