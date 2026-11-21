import { Component, inject, OnInit, computed } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DatePipe, SlicePipe } from '@angular/common';
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
  imports: [RouterLink, FormsModule, DatePipe, SlicePipe, SkeletonComponent, CopyButtonComponent],
  template: `
    <div class="section py-8">
      <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
        <div>
          <h1 class="text-h2 text-navy-900 dark:text-white">My URLs</h1>
          <p class="text-slate-500 dark:text-slate-400 mt-1">Manage and track all your shortened links.</p>
          @if (planService.plan(); as p) {
            <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
              {{ usage(p) }}@if (p.linkLifetimeDays) {; links last {{ p.linkLifetimeDays }} days and then disappear}.
            </p>
          }
        </div>
        <a routerLink="/urls/create" class="btn-accent">+ Create New</a>
      </div>

      <div class="flex flex-col sm:flex-row gap-3 mb-6">
        <input type="text" [(ngModel)]="searchTerm" (input)="updateFilter()" placeholder="Search URLs..." class="input-field max-w-xs" />
        <select [(ngModel)]="direction" (change)="changeDirection()" class="input-field max-w-[200px]">
          <option value="desc">Newest First</option>
          <option value="asc">Oldest First</option>
        </select>
      </div>

      @if (urlService.loading()) {
        <div class="card rounded-2xl space-y-3">
          @for (i of [1,2,3,4,5]; track i) { <app-skeleton height="40px" /> }
        </div>
      } @else if (displayUrls().length === 0 && !searchTerm) {
        <div class="card-elevated text-center py-16">
          <div class="text-5xl mb-4">📭</div>
          <h3 class="text-h3 text-navy-900 dark:text-white mb-2">No URLs yet</h3>
          <p class="text-slate-500 dark:text-slate-400 mb-6">Create your first short link to get started.</p>
          <a routerLink="/urls/create" class="btn-accent inline-block">Create URL</a>
        </div>
      } @else {
        <div class="card rounded-2xl overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-gray-100 dark:border-slate-700 text-left text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                <th class="pb-3">Short URL</th><th class="pb-3 hidden sm:table-cell">Original</th>@if (showClicks()) {<th class="pb-3 text-right">Clicks</th>}<th class="pb-3 hidden sm:table-cell">Created</th>@if (!showClicks()) {<th class="pb-3">Expires</th>}<th class="pb-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (url of displayUrls(); track url.id) {
                <tr class="border-b border-gray-50 dark:border-slate-800 last:border-0 hover:bg-navy-50 dark:hover:bg-slate-800/50 transition-colors">
                  <td class="py-3">
                    <div class="flex items-center gap-2">
                      <code class="font-mono text-orange-500 font-medium text-xs">{{ url.shortUrl }}</code>
                      <app-copy-button [text]="baseUrl + '/' + url.shortUrl" />
                    </div>
                  </td>
                  <td class="py-3 text-slate-500 dark:text-slate-400 max-w-[200px] truncate hidden sm:table-cell text-xs">{{ url.originalUrl }}</td>
                  @if (showClicks()) {
                    <td class="py-3 text-right font-bold text-navy-900 dark:text-white">{{ url.clickCount }}</td>
                  }
                  <td class="py-3 text-xs text-slate-400 hidden sm:table-cell">{{ url.createdDate | slice:0:10 }}</td>
                  @if (!showClicks()) {
                    <td class="py-3 text-xs text-slate-500 dark:text-slate-400">{{ url.expiresAt ? (url.expiresAt | date: 'MMM d, HH:mm') : 'Never' }}</td>
                  }
                  <td class="py-3 text-right">
                    <div class="flex gap-2 justify-end">
                      @if (showClicks()) {
                        <a [routerLink]="['/analytics', url.shortUrl]" class="text-xs font-medium text-navy-500 dark:text-navy-200 hover:text-orange-500 transition-colors">Stats</a>
                      }
                      <button (click)="deleteUrl(url)" class="text-xs font-medium text-red-500 hover:text-red-700 transition-colors">Delete</button>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>

          @if (pagination(); as p) {
            <div class="flex justify-between items-center mt-4 pt-4 border-t border-gray-100 dark:border-slate-700">
              <p class="text-xs text-slate-500 dark:text-slate-400">Showing {{ (pageIndex * p.size) + 1 }}–{{ min((pageIndex + 1) * p.size, p.totalElements) }} of {{ p.totalElements }}</p>
              <div class="flex gap-2">
                <button [disabled]="pageIndex === 0" (click)="previousPage()" class="px-3 py-1.5 rounded-md text-xs font-medium border border-gray-200 dark:border-slate-600 text-navy-900 dark:text-white hover:bg-navy-50 dark:hover:bg-slate-700 disabled:opacity-30 transition-colors">← Prev</button>
                <button [disabled]="!p.nextCursor || (pageIndex + 1) * p.size >= p.totalElements" (click)="nextPage(p.nextCursor)" class="px-3 py-1.5 rounded-md text-xs font-medium border border-gray-200 dark:border-slate-600 text-navy-900 dark:text-white hover:bg-navy-50 dark:hover:bg-slate-700 disabled:opacity-30 transition-colors">Next →</button>
              </div>
            </div>
          }
        </div>
      }
    </div>
  `,
})
export class MyUrlsPageComponent implements OnInit {
  urlService = inject(UrlService);
  planService = inject(PlanService);
  usage = describeUsage;
  /** Click counts only exist on plans with analytics. */
  showClicks = computed(() => this.planService.plan()?.analytics ?? false);
  searchTerm = '';
  direction = 'desc';
  pageIndex = 0;
  // Cursor for each visited page; the API only returns the next one, so Prev replays these.
  private cursors: (string | null)[] = [null];
  baseUrl = environment.baseRedirectUrl;

  displayUrls = computed(() => {
    const urls = this.urlService.urls();
    if (!this.searchTerm) return urls;
    const term = this.searchTerm.toLowerCase();
    return urls.filter((u: UrlMapping) => u.originalUrl.toLowerCase().includes(term) || u.shortUrl.toLowerCase().includes(term));
  });

  ngOnInit(): void { this.planService.fetchPlan(); this.loadUrls(); }

  loadUrls(): void { this.urlService.getMyUrls(this.cursors[this.pageIndex], 20, this.direction); }
  updateFilter(): void {}
  changeDirection(): void { this.cursors = [null]; this.pageIndex = 0; this.loadUrls(); }
  nextPage(cursor: string | null): void { this.pageIndex++; this.cursors[this.pageIndex] = cursor; this.loadUrls(); }
  previousPage(): void { this.pageIndex--; this.loadUrls(); }
  pagination() { return this.urlService.pagination(); }
  min(a: number, b: number): number { return Math.min(a, b); }

  deleteUrl(url: UrlMapping): void {
    if (confirm(`Delete short URL "${url.shortUrl}"?`)) {
      this.urlService.deleteUrl(url.id).subscribe(() => this.loadUrls());
    }
  }
}

