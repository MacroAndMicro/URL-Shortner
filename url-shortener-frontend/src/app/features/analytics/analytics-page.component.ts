import { Component, ElementRef, OnDestroy, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Chart, registerables } from 'chart.js';
import { AnalyticsService } from '../../core/services/analytics.service';
import { PlanService } from '../../core/services/plan.service';
import { ThemeService } from '../../core/services/theme.service';
import { UrlService } from '../../core/services/url.service';
import { ClickEventDetail } from '../../models/analytics.model';
import { describeDays } from '../../models/plan.model';
import { StatCardComponent } from '../../shared/components/stat-card.component';
import { SkeletonComponent } from '../../shared/components/skeleton.component';
import { CopyButtonComponent } from '../../shared/components/copy-button.component';
import { dayLabel, daysBetween, isoDay, lineChart, rankedBars } from '../../shared/charts/chart-style';
import { environment } from '../../environments/environment';

Chart.register(...registerables);

interface Range { label: string; days: number; }

@Component({
  selector: 'app-analytics-page',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, StatCardComponent, SkeletonComponent, CopyButtonComponent],
  template: `
    <div class="section py-10">
      <a routerLink="/urls" class="text-sm font-medium text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white">← My URLs</a>

      <div class="mt-3 mb-6 flex flex-col justify-between gap-4 lg:flex-row lg:items-end">
        <div class="min-w-0">
          <h1 class="text-h2 text-navy-900 dark:text-white">Link analytics</h1>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <a [href]="link()" target="_blank" rel="noopener" class="truncate font-semibold text-orange-600 hover:underline dark:text-orange-400">{{ host }}/{{ shortUrl() }}</a>
            <app-copy-button [text]="link()" />
          </div>
          @if (destination(); as d) {
            <p class="mt-1 truncate text-sm text-slate-500 dark:text-slate-400" [title]="d">{{ d }}</p>
          }
        </div>

        <div class="inline-flex self-start rounded-lg border border-slate-200 bg-white p-0.5 dark:border-slate-700 dark:bg-slate-900" role="group" aria-label="Time range">
          @for (r of ranges; track r.days) {
            <button type="button" (click)="setRange(r.days)" [attr.aria-pressed]="range() === r.days"
                    class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
                    [class]="range() === r.days
                      ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
                      : 'text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white'">
              {{ r.label }}
            </button>
          }
        </div>
      </div>

      @if (error()) {
        <div class="card-elevated py-12 text-center">
          <p class="mb-5 text-slate-700 dark:text-slate-200">{{ error() }}</p>
          <a routerLink="/plan" class="btn-accent">View plans</a>
        </div>
      } @else if (loading()) {
        <div class="space-y-6">
          <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
            @for (i of [1, 2, 3, 4]; track i) { <div class="card"><app-skeleton height="64px" /></div> }
          </div>
          <div class="card"><app-skeleton height="260px" /></div>
        </div>
      } @else {
        <div class="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
          <app-stat-card label="Clicks" [value]="(total() | number) ?? '0'" />
          <app-stat-card label="Unique visitors" [value]="(uniqueVisitors() | number) ?? '0'" />
          <app-stat-card label="Top source" [value]="topSource()" />
          <app-stat-card label="Daily average" [value]="(dailyAverage() | number: '1.0-1') ?? '0'" />
        </div>

        <section class="card mb-6" aria-labelledby="daily-title">
          <h2 id="daily-title" class="text-h3 mb-4 text-navy-900 dark:text-white">Clicks per day</h2>
          <div class="h-72"><canvas #lineChart role="img" [attr.aria-label]="'Clicks per day: ' + total() + ' in total'"></canvas></div>
          <table class="sr-only">
            <caption>Clicks per day</caption>
            <thead><tr><th scope="col">Day</th><th scope="col">Clicks</th></tr></thead>
            <tbody>@for (row of series(); track row.day) { <tr><td>{{ row.day }}</td><td>{{ row.count }}</td></tr> }</tbody>
          </table>
        </section>

        <div class="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-2">
          <section class="card" aria-labelledby="sources-title">
            <h2 id="sources-title" class="text-h3 mb-4 text-navy-900 dark:text-white">Sources</h2>
            @if (sources().length === 0) {
              <p class="text-sm text-slate-500 dark:text-slate-400">No clicks in this period.</p>
            } @else {
              <div [style.height.px]="barHeight(sources().length)"><canvas #sourcesChart role="img" aria-label="Clicks by source"></canvas></div>
              <table class="sr-only">
                <caption>Clicks by source</caption>
                <tbody>@for (s of sources(); track s[0]) { <tr><td>{{ s[0] }}</td><td>{{ s[1] }}</td></tr> }</tbody>
              </table>
            }
          </section>
          <section class="card" aria-labelledby="browsers-title">
            <h2 id="browsers-title" class="text-h3 mb-4 text-navy-900 dark:text-white">Browsers</h2>
            @if (browsers().length === 0) {
              <p class="text-sm text-slate-500 dark:text-slate-400">No clicks in this period.</p>
            } @else {
              <div [style.height.px]="barHeight(browsers().length)"><canvas #browsersChart role="img" aria-label="Clicks by browser"></canvas></div>
              <table class="sr-only">
                <caption>Clicks by browser</caption>
                <tbody>@for (b of browsers(); track b[0]) { <tr><td>{{ b[0] }}</td><td>{{ b[1] }}</td></tr> }</tbody>
              </table>
            }
          </section>
        </div>

        <section class="card" aria-labelledby="recent-title">
          <div class="mb-4 flex flex-wrap items-baseline justify-between gap-2">
            <h2 id="recent-title" class="text-h3 text-navy-900 dark:text-white">Recent clicks</h2>
            @if (retention(); as r) {
              <p class="text-xs text-slate-500 dark:text-slate-400">Individual clicks are kept for {{ r }} on your plan; daily totals for as long as the link exists.</p>
            }
          </div>
          @if (recent().length === 0) {
            <p class="text-sm text-slate-500 dark:text-slate-400">No clicks in this period.</p>
          } @else {
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b border-slate-200 text-left text-xs font-semibold uppercase tracking-wide text-slate-500 dark:border-slate-700 dark:text-slate-400">
                    <th scope="col" class="pb-2 pr-4">Time</th>
                    <th scope="col" class="pb-2 pr-4">Source</th>
                    <th scope="col" class="pb-2">Browser</th>
                  </tr>
                </thead>
                <tbody>
                  @for (c of recent(); track $index) {
                    <tr class="border-b border-slate-100 last:border-0 dark:border-slate-800">
                      <td class="py-2.5 pr-4 whitespace-nowrap text-slate-700 dark:text-slate-200">{{ c.clickDate | date: 'MMM d, h:mm a' }}</td>
                      <td class="py-2.5 pr-4 text-slate-600 dark:text-slate-300">{{ sourceOf(c) }}</td>
                      <td class="py-2.5 text-slate-600 dark:text-slate-300">{{ browserOf(c) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (details().length > recent().length) {
              <p class="mt-3 text-xs text-slate-500 dark:text-slate-400">Showing the latest {{ recent().length }} of {{ details().length }} clicks.</p>
            }
          }
        </section>
      }
    </div>
  `,
})
export class AnalyticsPageComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly analyticsService = inject(AnalyticsService);
  private readonly urlService = inject(UrlService);
  private readonly planService = inject(PlanService);
  private readonly theme = inject(ThemeService);

  host = environment.baseRedirectUrl.replace(/^https?:\/\//, '');
  readonly ranges: Range[] = [
    { label: '7 days', days: 7 }, { label: '30 days', days: 30 }, { label: '90 days', days: 90 }, { label: '1 year', days: 365 },
  ];

  shortUrl = signal('');
  range = signal(30);
  loading = signal(false);
  error = signal<string | null>(null);
  series = signal<{ day: string; count: number }[]>([]);
  details = signal<ClickEventDetail[]>([]);

  link = computed(() => `${environment.baseRedirectUrl}/${this.shortUrl()}`);
  /** Known when arriving from My URLs, which has already loaded the link. */
  destination = computed(() => this.urlService.urls().find((u) => u.shortUrl === this.shortUrl())?.originalUrl ?? null);
  retention = computed(() => {
    const days = this.planService.plan()?.clickDetailRetentionDays;
    return days ? describeDays(days) : null;
  });

  total = computed(() => this.series().reduce((sum, r) => sum + r.count, 0));
  uniqueVisitors = computed(() => new Set(this.details().map((d) => d.ipAddress).filter(Boolean)).size);
  dailyAverage = computed(() => (this.series().length ? this.total() / this.series().length : 0));
  sources = computed(() => topWithOther(countBy(this.details().map((d) => this.sourceOf(d))), 6));
  browsers = computed(() => topWithOther(countBy(this.details().map((d) => this.browserOf(d))), 6));
  topSource = computed(() => this.sources()[0]?.[0] ?? '—');
  recent = computed(() =>
    [...this.details()].sort((a, b) => b.clickDate.localeCompare(a.clickDate)).slice(0, 25));

  private lineRef = viewChild<ElementRef<HTMLCanvasElement>>('lineChart');
  private sourcesRef = viewChild<ElementRef<HTMLCanvasElement>>('sourcesChart');
  private browsersRef = viewChild<ElementRef<HTMLCanvasElement>>('browsersChart');
  private charts: Chart[] = [];

  constructor() {
    // Draw whenever the canvases appear, the data changes or the theme changes.
    effect(() => {
      this.lineRef(); this.sourcesRef(); this.browsersRef();
      this.series(); this.details();
      this.theme.theme();
      this.draw();
    });
  }

  ngOnInit(): void {
    this.shortUrl.set(this.route.snapshot.paramMap.get('shortUrl') ?? '');
    this.planService.fetchPlan();
    this.load();
  }

  ngOnDestroy(): void {
    this.destroyCharts();
  }

  setRange(days: number): void {
    this.range.set(days);
    this.load();
  }

  barHeight(rows: number): number {
    return rows * 36 + 32;
  }

  sourceOf(click: ClickEventDetail): string {
    if (!click.referrer) return 'Direct';
    try { return new URL(click.referrer).hostname.replace(/^www\./, ''); } catch { return click.referrer.slice(0, 30); }
  }

  browserOf(click: ClickEventDetail): string {
    return browserName(click.userAgent);
  }

  private load(): void {
    const end = new Date();
    const start = new Date();
    start.setDate(end.getDate() - (this.range() - 1));
    const days = daysBetween(start, end);
    const from = `${isoDay(start)}T00:00:00`;
    const to = `${isoDay(end)}T23:59:59`;

    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      daily: this.analyticsService.getAnalytics(this.shortUrl(), from, to),
      details: this.analyticsService.getClickDetails(this.shortUrl(), from, to),
    }).subscribe({
      next: ({ daily, details }) => {
        const byDay = new Map(daily.data.map((d) => [d.clickDate, d.count]));
        this.series.set(days.map((day) => ({ day, count: byDay.get(day) ?? 0 })));
        this.details.set(details.data);
        this.loading.set(false);
      },
      error: (err) => {
        // e.g. 403 on plans without analytics; the backend explains why.
        this.error.set(err.error?.message ?? 'Could not load analytics for this link.');
        this.loading.set(false);
      },
    });
  }

  private draw(): void {
    this.destroyCharts();
    const dark = this.theme.isDark();
    const line = this.lineRef()?.nativeElement;
    if (line && this.series().length) {
      const rows = this.series();
      this.charts.push(new Chart(line, lineChart(rows.map((r) => dayLabel(r.day)), rows.map((r) => r.count), dark)));
    }
    const sources = this.sourcesRef()?.nativeElement;
    if (sources && this.sources().length) {
      this.charts.push(new Chart(sources, rankedBars(this.sources().map((s) => s[0]), this.sources().map((s) => s[1]), dark)));
    }
    const browsers = this.browsersRef()?.nativeElement;
    if (browsers && this.browsers().length) {
      this.charts.push(new Chart(browsers, rankedBars(this.browsers().map((b) => b[0]), this.browsers().map((b) => b[1]), dark)));
    }
  }

  private destroyCharts(): void {
    this.charts.forEach((c) => c.destroy());
    this.charts = [];
  }
}

/** Occurrences of each value, largest first. */
function countBy(values: string[]): [string, number][] {
  const counts = new Map<string, number>();
  for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

/** Keeps the first max - 1 entries and folds the rest into "Other". */
function topWithOther(sorted: [string, number][], max: number): [string, number][] {
  if (sorted.length <= max) return sorted;
  const rest = sorted.slice(max - 1).reduce((sum, [, n]) => sum + n, 0);
  return [...sorted.slice(0, max - 1), ['Other', rest]];
}

/** Browser family from a User-Agent. Order matters: Edge and Opera also say "Chrome", Chrome also says "Safari". */
function browserName(userAgent: string | null): string {
  if (!userAgent) return 'Unknown';
  if (/Edg\//.test(userAgent)) return 'Edge';
  if (/OPR\/|Opera/.test(userAgent)) return 'Opera';
  if (/Firefox\//.test(userAgent)) return 'Firefox';
  if (/Chrome\/|CriOS\//.test(userAgent)) return 'Chrome';
  if (/Safari\//.test(userAgent)) return 'Safari';
  return 'Other';
}
