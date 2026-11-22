import { Component, ElementRef, OnDestroy, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Chart, registerables } from 'chart.js';
import { AnalyticsService } from '../../core/services/analytics.service';
import { PlanService } from '../../core/services/plan.service';
import { ThemeService } from '../../core/services/theme.service';
import { UrlService } from '../../core/services/url.service';
import { PLAN_LABELS, describeUsage } from '../../models/plan.model';
import { StatCardComponent } from '../../shared/components/stat-card.component';
import { SkeletonComponent } from '../../shared/components/skeleton.component';
import { dayLabel, daysBetween, lineChart } from '../../shared/charts/chart-style';
import { environment } from '../../environments/environment';

Chart.register(...registerables);

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [RouterLink, DecimalPipe, DatePipe, StatCardComponent, SkeletonComponent],
  template: `
    <div class="section py-10">
      <div class="mb-8 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 class="text-h2 text-navy-900 dark:text-white">Dashboard</h1>
          <p class="mt-1 text-slate-500 dark:text-slate-400">Your links at a glance.</p>
        </div>
        <a routerLink="/urls/create" class="btn-accent">Create link</a>
      </div>

      @if (planService.plan(); as p) {
        @if (p.inGracePeriod) {
          <p class="alert-warning mb-6" role="status">
            Your payment is overdue. Pay by {{ p.graceEndsAt | date: 'mediumDate' }} to keep your {{ labels[p.subscribedPlan] }} plan.
            <a routerLink="/plan" fragment="pay" class="font-semibold underline">Pay now</a>
          </p>
        }
        @if (p.paidLinksExpireAt) {
          <p class="alert-error mb-6" role="status">
            Your {{ labels[p.subscribedPlan] }} plan has lapsed. The links you made on it stop working on
            {{ p.paidLinksExpireAt | date: 'medium' }} unless you renew. <a routerLink="/plan" fragment="pay" class="font-semibold underline">Pay now</a>
          </p>
        }

        <!-- Plan and usage -->
        <div class="card mb-6">
          <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0 flex-1">
              <p class="text-sm font-medium text-slate-500 dark:text-slate-400">{{ labels[p.effectivePlan] }} plan</p>
              <p class="mt-0.5 text-lg font-semibold text-slate-900 dark:text-white">{{ usage(p) }}</p>
              <div class="mt-3 h-2 max-w-md overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800"
                   role="progressbar" [attr.aria-label]="usage(p)" [attr.aria-valuenow]="p.linksUsed" aria-valuemin="0" [attr.aria-valuemax]="p.linkLimit">
                <div class="h-full rounded-full bg-orange-500" [style.width.%]="percent(p.linksUsed, p.linkLimit)"></div>
              </div>
            </div>
            <a routerLink="/plan" class="btn-outline shrink-0">View plan</a>
          </div>
        </div>

        @if (p.analytics) {
          @if (analytics.loading() || !analytics.dashboard()) {
            <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
              @for (i of [1, 2, 3]; track i) { <div class="card"><app-skeleton height="64px" /></div> }
            </div>
          } @else if (analytics.dashboard(); as d) {
            <div class="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
              <app-stat-card label="Total clicks" [value]="(d.totalClicks | number) ?? '0'" [comparison]="'Across ' + d.totalUrls + (d.totalUrls === 1 ? ' link' : ' links')" />
              <app-stat-card label="Clicks today" [value]="(d.clicksToday | number) ?? '0'" />
              <app-stat-card label="Last 7 days" [value]="(d.clicksThisWeek | number) ?? '0'" />
            </div>

            <!-- Clicks over time -->
            <section class="card mb-6" aria-labelledby="clicks-title">
              <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
                <h2 id="clicks-title" class="text-h3 text-navy-900 dark:text-white">Clicks per day</h2>
                <div class="inline-flex rounded-lg border border-slate-200 p-0.5 dark:border-slate-700" role="group" aria-label="Time range">
                  @for (r of ranges; track r) {
                    <button type="button" (click)="setRange(r)" [attr.aria-pressed]="range() === r"
                            class="rounded-md px-3 py-1 text-sm font-medium transition-colors"
                            [class]="range() === r
                              ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
                              : 'text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white'">
                      {{ r }} days
                    </button>
                  }
                </div>
              </div>
              <div class="h-64">
                <canvas #chartCanvas role="img" [attr.aria-label]="chartSummary()"></canvas>
              </div>
              <table class="sr-only">
                <caption>Clicks per day, last {{ range() }} days</caption>
                <thead><tr><th scope="col">Day</th><th scope="col">Clicks</th></tr></thead>
                <tbody>
                  @for (row of series(); track row.day) { <tr><td>{{ row.day }}</td><td>{{ row.count }}</td></tr> }
                </tbody>
              </table>
            </section>

            <!-- Top links -->
            <section class="card" aria-labelledby="top-title">
              <h2 id="top-title" class="text-h3 mb-4 text-navy-900 dark:text-white">Top links</h2>
              @if (d.topUrls.length === 0) {
                <p class="text-sm text-slate-500 dark:text-slate-400">No links yet.</p>
              } @else {
                <ol class="space-y-4">
                  @for (url of d.topUrls; track url.id) {
                    <li>
                      <div class="flex items-baseline justify-between gap-4">
                        <a [routerLink]="['/analytics', url.shortUrl]" class="min-w-0 truncate font-medium text-slate-900 hover:text-orange-600 dark:text-white dark:hover:text-orange-400">
                          {{ host }}/{{ url.shortUrl }}
                        </a>
                        <span class="shrink-0 text-sm font-semibold text-slate-900 dark:text-white">{{ url.clickCount | number }}</span>
                      </div>
                      <p class="truncate text-xs text-slate-500 dark:text-slate-400">{{ url.originalUrl }}</p>
                      <div class="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800" aria-hidden="true">
                        <div class="h-full rounded-full bg-orange-500" [style.width.%]="percent(url.clickCount, topMax())"></div>
                      </div>
                    </li>
                  }
                </ol>
              }
            </section>
          }
        } @else {
          <!-- Plans without analytics -->
          <div class="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
            <section class="card" aria-labelledby="recent-title">
              <div class="mb-4 flex items-center justify-between">
                <h2 id="recent-title" class="text-h3 text-navy-900 dark:text-white">Recent links</h2>
                <a routerLink="/urls" class="text-sm font-medium text-orange-600 hover:underline dark:text-orange-400">All links</a>
              </div>
              @if (urlService.urls().length === 0) {
                <p class="text-sm text-slate-500 dark:text-slate-400">No links yet. <a routerLink="/urls/create" class="font-medium text-orange-600 dark:text-orange-400">Create one</a>.</p>
              } @else {
                <ul class="divide-y divide-slate-200 dark:divide-slate-700">
                  @for (url of urlService.urls().slice(0, 5); track url.id) {
                    <li class="py-3 first:pt-0 last:pb-0">
                      <p class="truncate font-medium text-slate-900 dark:text-white">{{ host }}/{{ url.shortUrl }}</p>
                      <p class="truncate text-xs text-slate-500 dark:text-slate-400">{{ url.originalUrl }}</p>
                      @if (url.expiresAt) {
                        <p class="mt-0.5 text-xs text-slate-500 dark:text-slate-400">Expires {{ url.expiresAt | date: 'MMM d, h:mm a' }}</p>
                      }
                    </li>
                  }
                </ul>
              }
            </section>

            <aside class="card border-dashed" aria-labelledby="locked-title">
              <h2 id="locked-title" class="text-h3 text-navy-900 dark:text-white">Click analytics</h2>
              <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">On the Monthly and Yearly plans you can see:</p>
              <ul class="mt-3 space-y-1.5 text-sm text-slate-700 dark:text-slate-300">
                <li>Clicks per day for every link</li>
                <li>Where visitors came from</li>
                <li>Which browsers they used</li>
              </ul>
              <a routerLink="/plan" class="btn-outline mt-5 w-full">See plans</a>
            </aside>
          </div>
        }
      } @else if (planService.error()) {
        <p class="alert-error" role="alert">{{ planService.error() }}</p>
      } @else {
        <div class="card"><app-skeleton height="80px" /></div>
      }
    </div>
  `,
})
export class DashboardPageComponent implements OnInit, OnDestroy {
  analytics = inject(AnalyticsService);
  planService = inject(PlanService);
  urlService = inject(UrlService);
  private theme = inject(ThemeService);

  usage = describeUsage;
  labels = PLAN_LABELS;
  host = environment.baseRedirectUrl.replace(/^https?:\/\//, '');

  readonly ranges = [7, 30] as const;
  range = signal<7 | 30>(7);
  series = signal<{ day: string; count: number }[]>([]);

  topMax = computed(() => Math.max(1, ...(this.analytics.dashboard()?.topUrls.map((u) => u.clickCount) ?? [0])));
  chartSummary = computed(() => {
    const total = this.series().reduce((sum, r) => sum + r.count, 0);
    return `Clicks per day over the last ${this.range()} days: ${total} in total.`;
  });

  chartCanvas = viewChild<ElementRef<HTMLCanvasElement>>('chartCanvas');
  private chart: Chart | null = null;

  constructor() {
    // Load once the canvas exists (after the dashboard data arrives).
    effect(() => {
      if (this.chartCanvas()) this.loadSeries();
    });
    // Redraw in the new colours when the theme changes.
    effect(() => {
      this.theme.theme();
      this.draw();
    });
  }

  ngOnInit(): void {
    // Analytics endpoints refuse plans without analytics, so check the plan first.
    this.planService.load().subscribe({
      next: (plan) => {
        if (plan.analytics) {
          this.analytics.fetchDashboard();
        } else {
          this.urlService.getMyUrls(null, 5, 'desc');
        }
      },
      error: () => {},
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  percent(value: number, max: number): number {
    return max > 0 ? Math.min(100, (value / max) * 100) : 0;
  }

  setRange(days: 7 | 30): void {
    this.range.set(days);
    this.loadSeries();
  }

  private loadSeries(): void {
    const end = new Date();
    const start = new Date();
    start.setDate(end.getDate() - (this.range() - 1));
    const days = daysBetween(start, end);
    this.analytics.getTotalClicks(days[0], days[days.length - 1]).subscribe({
      next: (res) => {
        const byDay = new Map(res.data.map((d) => [d.clickDate, d.count]));
        this.series.set(days.map((day) => ({ day, count: byDay.get(day) ?? 0 })));
        this.draw();
      },
      error: () => {
        this.series.set(days.map((day) => ({ day, count: 0 })));
        this.draw();
      },
    });
  }

  private draw(): void {
    const canvas = this.chartCanvas()?.nativeElement;
    const rows = this.series();
    if (!canvas || rows.length === 0) return;
    this.chart?.destroy();
    this.chart = new Chart(canvas, lineChart(rows.map((r) => dayLabel(r.day)), rows.map((r) => r.count), this.theme.isDark()));
  }
}
