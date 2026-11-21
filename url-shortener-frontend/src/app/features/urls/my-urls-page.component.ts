import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { PlanService } from '../../core/services/plan.service';
import { describeUsage } from '../../models/plan.model';
import { UrlService } from '../../core/services/url.service';
import { SkeletonComponent } from '../../shared/components/skeleton.component';
import { CopyButtonComponent } from '../../shared/components/copy-button.component';
import { UrlMapping } from '../../models/url-mapping.model';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-my-urls-page',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe, SkeletonComponent, CopyButtonComponent],
  template: `
    <div class="section py-10">
      <div class="mb-8 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 class="text-h2 text-navy-900 dark:text-white">My URLs</h1>
          @if (planService.plan(); as p) {
            <p class="mt-1 text-slate-500 dark:text-slate-400">
              {{ usage(p) }}@if (p.linkLifetimeDays) {. Links last {{ p.linkLifetimeDays }} days}.
            </p>
          }
        </div>
        <a routerLink="/urls/create" class="btn-accent">Create link</a>
      </div>

      <div class="mb-5 flex flex-col gap-3 sm:flex-row">
        <label for="url-search" class="sr-only">Search links</label>
        <input id="url-search" type="search" [ngModel]="search()" (ngModelChange)="search.set($event)"
               placeholder="Search this page by link or destination" class="input-field sm:max-w-sm" />
        <label for="url-order" class="sr-only">Order</label>
        <select id="url-order" [(ngModel)]="direction" (change)="changeDirection()" class="input-field sm:max-w-[12rem]">
          <option value="desc">Newest first</option>
          <option value="asc">Oldest first</option>
        </select>
      </div>

      @if (urlService.loading()) {
        <div class="card space-y-3">
          @for (i of [1, 2, 3, 4]; track i) { <app-skeleton height="56px" /> }
        </div>
      } @else if (urlService.error()) {
        <div class="alert-error" role="alert">{{ urlService.error() }}</div>
      } @else if (urlService.urls().length === 0) {
        <div class="card-elevated py-14 text-center">
          <h2 class="text-h3 text-navy-900 dark:text-white">No links yet</h2>
          <p class="mt-1 mb-6 text-slate-500 dark:text-slate-400">Create your first short link to see it here.</p>
          <a routerLink="/urls/create" class="btn-accent">Create link</a>
        </div>
      } @else {
        @if (displayUrls().length === 0) {
          <p class="card text-center text-sm text-slate-500 dark:text-slate-400">No links on this page match "{{ search() }}".</p>
        }
        <ul class="space-y-3">
          @for (url of displayUrls(); track url.id) {
            <li class="card">
              <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <a [href]="link(url)" target="_blank" rel="noopener"
                       class="truncate font-semibold text-orange-600 hover:underline dark:text-orange-400">{{ host }}/{{ url.shortUrl }}</a>
                    <app-copy-button [text]="link(url)" />
                  </div>
                  <p class="mt-1 truncate text-sm text-slate-600 dark:text-slate-300" [title]="url.originalUrl">{{ url.originalUrl }}</p>
                  <p class="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500 dark:text-slate-400">
                    <span>Created {{ url.createdDate | date: 'MMM d, y, h:mm a' }}</span>
                    @if (url.expiresAt) {
                      <span>Expires {{ url.expiresAt | date: 'MMM d, h:mm a' }}</span>
                    }
                    @if (showClicks()) {
                      <span class="font-medium text-slate-700 dark:text-slate-200">{{ url.clickCount }} {{ url.clickCount === 1 ? 'click' : 'clicks' }}</span>
                    }
                    @if (url.editableUntil && canEdit(url)) {
                      <span>Editable for {{ timeLeft(url.editableUntil) }}</span>
                    }
                  </p>
                </div>

                <div class="flex shrink-0 gap-2">
                  @if (canEdit(url)) {
                    <button type="button" class="btn-outline !px-3 !py-1.5 !text-xs" (click)="startEdit(url)"
                            [attr.aria-expanded]="editingId() === url.id" [attr.aria-controls]="'edit-' + url.id">Edit</button>
                  }
                  @if (showClicks()) {
                    <a [routerLink]="['/analytics', url.shortUrl]" class="btn-outline !px-3 !py-1.5 !text-xs">Stats</a>
                  }
                  <button type="button" class="btn-ghost !px-3 !py-1.5 !text-xs !text-red-600 dark:!text-red-400" (click)="deleteUrl(url)">Delete</button>
                </div>
              </div>

              @if (editingId() === url.id) {
                <form [id]="'edit-' + url.id" class="mt-4 border-t border-slate-200 pt-4 dark:border-slate-700" (ngSubmit)="saveEdit(url)">
                  <label [for]="'edit-input-' + url.id" class="field-label">Send this link to</label>
                  <div class="flex flex-col gap-2 sm:flex-row">
                    <input [id]="'edit-input-' + url.id" type="url" name="destination" class="input-field"
                           [ngModel]="draft()" (ngModelChange)="draft.set($event)" required />
                    <div class="flex gap-2">
                      <button type="submit" class="btn-accent" [disabled]="saving() || !validDraft()">
                        @if (saving()) { Saving… } @else { Save }
                      </button>
                      <button type="button" class="btn-ghost" (click)="cancelEdit()">Cancel</button>
                    </div>
                  </div>
                  @if (!validDraft()) {
                    <p class="mt-1.5 text-sm text-red-600 dark:text-red-400">Enter a link that starts with http:// or https://</p>
                  }
                  @if (editError()) {
                    <p class="mt-2 alert-error" role="alert">{{ editError() }}</p>
                  }
                </form>
              }
            </li>
          }
        </ul>

        @if (urlService.pagination(); as p) {
          <div class="mt-5 flex items-center justify-between">
            <p class="text-sm text-slate-500 dark:text-slate-400">
              Showing {{ pageIndex * p.size + 1 }}–{{ min((pageIndex + 1) * p.size, p.totalElements) }} of {{ p.totalElements }}
            </p>
            <div class="flex gap-2">
              <button type="button" class="btn-outline !py-1.5" [disabled]="pageIndex === 0" (click)="previousPage()">Previous</button>
              <button type="button" class="btn-outline !py-1.5" [disabled]="!p.nextCursor || (pageIndex + 1) * p.size >= p.totalElements" (click)="nextPage(p.nextCursor)">Next</button>
            </div>
          </div>
        }
      }
    </div>
  `,
})
export class MyUrlsPageComponent implements OnInit, OnDestroy {
  urlService = inject(UrlService);
  planService = inject(PlanService);
  usage = describeUsage;
  host = environment.baseRedirectUrl.replace(/^https?:\/\//, '');

  /** Click counts only exist on plans with analytics. */
  showClicks = computed(() => this.planService.plan()?.analytics ?? false);

  search = signal('');
  displayUrls = computed(() => {
    const term = this.search().trim().toLowerCase();
    const urls = this.urlService.urls();
    return term
      ? urls.filter((u: UrlMapping) => u.originalUrl.toLowerCase().includes(term) || u.shortUrl.toLowerCase().includes(term))
      : urls;
  });

  direction = 'desc';
  pageIndex = 0;
  // Cursor for each visited page; the API only returns the next one, so Previous replays these.
  private cursors: (string | null)[] = [null];

  editingId = signal<string | null>(null);
  draft = signal('');
  saving = signal(false);
  editError = signal<string | null>(null);
  validDraft = computed(() => /^https?:\/\/.+/.test(this.draft().trim()));

  /** Re-evaluated every 30 s so Edit buttons disappear when their window closes. */
  private now = signal(Date.now());
  private timer = setInterval(() => this.now.set(Date.now()), 30_000);

  ngOnInit(): void {
    this.planService.fetchPlan();
    this.loadUrls();
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  link(url: UrlMapping): string {
    return `${environment.baseRedirectUrl}/${url.shortUrl}`;
  }

  canEdit(url: UrlMapping): boolean {
    return !url.editableUntil || new Date(url.editableUntil).getTime() > this.now();
  }

  /** e.g. "2 h 14 min" or "9 min". */
  timeLeft(until: string): string {
    const minutes = Math.max(0, Math.floor((new Date(until).getTime() - this.now()) / 60_000));
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return h > 0 ? `${h} h ${m} min` : `${m} min`;
  }

  startEdit(url: UrlMapping): void {
    this.editingId.set(url.id);
    this.draft.set(url.originalUrl);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editError.set(null);
  }

  saveEdit(url: UrlMapping): void {
    if (!this.validDraft()) return;
    this.saving.set(true);
    this.editError.set(null);
    this.urlService.updateUrl(url.id, { originalUrl: this.draft().trim() }).subscribe({
      next: (res) => {
        this.urlService.urls.update((list) => list.map((u) => (u.id === url.id ? res.data : u)));
        this.saving.set(false);
        this.editingId.set(null);
      },
      error: (err) => {
        // e.g. the edit window closed while the form was open.
        this.editError.set(err.error?.message ?? 'Could not save the change.');
        this.saving.set(false);
      },
    });
  }

  loadUrls(): void {
    this.urlService.getMyUrls(this.cursors[this.pageIndex], 20, this.direction);
  }

  changeDirection(): void {
    this.cursors = [null];
    this.pageIndex = 0;
    this.loadUrls();
  }

  nextPage(cursor: string | null): void {
    this.pageIndex++;
    this.cursors[this.pageIndex] = cursor;
    this.loadUrls();
  }

  previousPage(): void {
    this.pageIndex--;
    this.loadUrls();
  }

  min(a: number, b: number): number {
    return Math.min(a, b);
  }

  deleteUrl(url: UrlMapping): void {
    if (confirm(`Delete ${this.host}/${url.shortUrl}? This cannot be undone.`)) {
      this.urlService.deleteUrl(url.id).subscribe(() => {
        this.loadUrls();
        this.planService.fetchPlan();
      });
    }
  }
}
