import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { SearchResult } from '../../core/models/models';
import { HelpCard } from '../../shared/components/help-card';

@Component({
  selector: 'app-search',
  imports: [FormsModule, RouterLink, MoneyPipe, HelpCard],
  templateUrl: './search.html',
  styleUrl: './search.scss',
})
export class Search implements OnDestroy {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  readonly examples = [
    'my last water bill',
    'most expensive shopping',
    'all Nike purchases',
    'electricity from July',
  ];

  q = '';
  result = signal<SearchResult | null>(null);
  loading = signal(false);
  status = signal('');
  private timer: ReturnType<typeof setInterval> | null = null;

  private readonly STAGES = [
    'Understanding your question…',
    'Scanning your documents…',
    'Ranking the best matches…',
    'Almost there…',
  ];

  runExample(example: string): void {
    this.q = example;
    this.run();
  }

  run(): void {
    if (!this.q.trim()) return;
    this.loading.set(true);
    this.result.set(null);
    this.startStatus();
    this.api.search(this.q, this.spaceCtx.currentSpaceId()).subscribe({
      next: (r) => {
        this.result.set(r);
        this.stop();
      },
      error: () => this.stop(),
    });
  }

  private startStatus(): void {
    let i = 0;
    this.status.set(this.STAGES[0]);
    this.timer = setInterval(() => {
      i = Math.min(i + 1, this.STAGES.length - 1);
      this.status.set(this.STAGES[i]);
    }, 1200);
  }

  private stop(): void {
    this.loading.set(false);
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  ngOnDestroy(): void {
    this.stop();
  }

  interpreted(r: SearchResult, key: string): string {
    const v = r.interpreted?.[key];
    return v == null || v === '' ? '-' : String(v);
  }
}
