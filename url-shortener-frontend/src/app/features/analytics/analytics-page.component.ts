import { Component, inject, OnInit, OnDestroy, signal, viewChild, ElementRef } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { SlicePipe } from '@angular/common';
import { Chart, registerables } from 'chart.js';
import { AnalyticsService } from '../../core/services/analytics.service';
import { StatCardComponent } from '../../shared/components/stat-card.component';
import { SkeletonComponent } from '../../shared/components/skeleton.component';
import { DailyClickCount, ClickEventDetail } from '../../models/analytics.model';

Chart.register(...registerables);

@Component({
  selector: 'app-analytics-page',
  standalone: true,
  imports: [RouterLink, FormsModule, SlicePipe, StatCardComponent, SkeletonComponent],
  template: `
    <div class="section py-8">
      <h1 class="text-h2 mb-1 text-navy-900 dark:text-white">Link Analytics</h1>
      <p class="text-slate-500 dark:text-slate-400 mb-2">
        <code class="font-mono text-orange-500 bg-navy-50 dark:bg-slate-800 px-2 py-0.5 rounded text-sm">{{ shortUrl() }}</code>
      </p>

      <!-- Period presets -->
      <div class="flex gap-2 mb-6 flex-wrap">
        @for (p of presets; track p.label) {
          <button (click)="applyPreset(p.days)" class="px-4 py-2 rounded-lg text-sm font-medium transition-all duration-150"
                  [class.bg-navy-900]="activePreset() === p.days"
                  [class.text-white]="activePreset() === p.days"
                  [class.bg-white]="activePreset() !== p.days"
                  [class.dark:bg-slate-800]="activePreset() !== p.days"
                  [class.text-slate-600]="activePreset() !== p.days"
                  [class.dark:text-slate-300]="activePreset() !== p.days"
                  [class.border]="activePreset() !== p.days"
                  [class.border-gray-200]="activePreset() !== p.days"
                  [class.dark:border-slate-600]="activePreset() !== p.days">
            {{ p.label }}
          </button>
        }
        <div class="flex gap-2 items-center">
          <input type="date" [(ngModel)]="startDate" class="input-field w-auto text-xs" />
          <span class="text-slate-400 text-xs">to</span>
          <input type="date" [(ngModel)]="endDate" class="input-field w-auto text-xs" />
          <button (click)="loadData()" class="btn-accent text-xs py-2 px-4">Apply</button>
        </div>
      </div>

      @if (error()) {
        <div class="card-elevated text-center py-12">
          <p class="text-navy-900 dark:text-white mb-4">{{ error() }}</p>
          <a routerLink="/plan" class="btn-accent inline-block">View plans</a>
        </div>
      } @else if (loading()) {
        <div class="space-y-6">
          <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
            @for (i of [1,2,3,4]; track i) { <div class="card rounded-2xl"><app-skeleton height="70px" /></div> }
          </div>
          <div class="card rounded-2xl"><app-skeleton height="250px" /></div>
        </div>
      } @else {
        <!-- Summary cards -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <app-stat-card label="Total Clicks" [value]="totalClicks()" icon="👆" accent="orange" />
          <app-stat-card label="Unique IPs" [value]="uniqueIPs()" icon="🌐" accent="blue" />
          <app-stat-card label="Top Referrer" [value]="topReferrer()" icon="🔗" accent="green" />
          <app-stat-card label="Avg Daily" [value]="avgDaily()" icon="📊" accent="purple" />
        </div>

        <!-- Line chart -->
        <div class="card rounded-2xl mb-6">
          <h3 class="text-h3 mb-4 text-navy-900 dark:text-white">Daily Clicks</h3>
          <div class="h-72"><canvas #lineChart></canvas></div>
        </div>

        <!-- Doughnut + Bar row -->
        <div class="grid md:grid-cols-2 gap-6 mb-6">
          <div class="card rounded-2xl">
            <h3 class="text-h3 mb-4 text-navy-900 dark:text-white">Referrer Breakdown</h3>
            <div class="h-56"><canvas #doughnutChart></canvas></div>
          </div>
          <div class="card rounded-2xl">
            <h3 class="text-h3 mb-4 text-navy-900 dark:text-white">Top User Agents</h3>
            <div class="h-56"><canvas #barChart></canvas></div>
          </div>
        </div>

        <!-- Click log -->
        @if (details().length > 0) {
          <div class="card rounded-2xl">
            <h3 class="text-h3 mb-4 text-navy-900 dark:text-white">Recent Clicks ({{ details().length }})</h3>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b border-gray-100 dark:border-slate-700 text-left text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                    <th class="pb-3">Time</th><th class="pb-3">IP</th><th class="pb-3 hidden sm:table-cell">Referrer</th><th class="pb-3 hidden sm:table-cell">User Agent</th>
                  </tr>
                </thead>
                <tbody>
                  @for (d of details(); track d.id) {
                    <tr class="border-b border-gray-50 dark:border-slate-800 last:border-0">
                      <td class="py-2.5 text-xs text-slate-500 dark:text-slate-400 font-mono">{{ d.clickDate | slice:0:19 }}</td>
                      <td class="py-2.5 text-xs font-mono text-navy-900 dark:text-white">{{ d.ipAddress ?? '—' }}</td>
                      <td class="py-2.5 text-xs text-slate-500 max-w-[180px] truncate hidden sm:table-cell">{{ d.referrer ?? '—' }}</td>
                      <td class="py-2.5 text-xs text-slate-400 max-w-[250px] truncate hidden sm:table-cell">{{ d.userAgent ?? '—' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      }
    </div>
  `,
})
export class AnalyticsPageComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly analyticsService = inject(AnalyticsService);

  lineChartRef = viewChild<ElementRef<HTMLCanvasElement>>('lineChart');
  doughnutChartRef = viewChild<ElementRef<HTMLCanvasElement>>('doughnutChart');
  barChartRef = viewChild<ElementRef<HTMLCanvasElement>>('barChart');

  shortUrl = signal('');
  startDate = '';
  endDate = '';
  loading = signal(false);
  activePreset = signal(7);
  clicks = signal<DailyClickCount[]>([]);
  details = signal<ClickEventDetail[]>([]);
  error = signal<string | null>(null);

  presets = [
    { label: '7 days', days: 7 }, { label: '30 days', days: 30 }, { label: '90 days', days: 90 }, { label: 'All time', days: 365 },
  ];

  private chartInstances: Chart[] = [];

  ngOnInit(): void {
    const url = this.route.snapshot.paramMap.get('shortUrl') ?? '';
    this.shortUrl.set(url);
    this.applyPreset(7);
  }

  ngOnDestroy(): void { this.destroyCharts(); }

  applyPreset(days: number): void {
    this.activePreset.set(days);
    const today = new Date().toISOString().slice(0, 10);
    const past = new Date(Date.now() - days * 86400000).toISOString().slice(0, 10);
    this.startDate = past;
    this.endDate = today;
    this.loadData();
  }

  loadData(): void {
    const start = `${this.startDate}T00:00:00`;
    const end = `${this.endDate}T23:59:59`;
    this.loading.set(true);
    this.error.set(null);

    // Both requests must finish before the charts are drawn.
    forkJoin({
      clicks: this.analyticsService.getAnalytics(this.shortUrl(), start, end),
      details: this.analyticsService.getClickDetails(this.shortUrl(), start, end),
    }).subscribe({
      next: ({ clicks, details }) => {
        this.clicks.set(clicks.data);
        this.details.set(details.data);
        this.loading.set(false);
        setTimeout(() => this.buildCharts());
      },
      error: (err) => {
        // e.g. 403 on plans without analytics; the backend explains why.
        this.error.set(err.error?.message ?? 'Could not load analytics for this link.');
        this.loading.set(false);
      },
    });
  }

  /** Click counts per referring site, largest first; no referrer counts as Direct. */
  private referrerCounts(): [string, number][] {
    return countBy(this.details().map((d) => {
      if (!d.referrer) return 'Direct';
      try { return new URL(d.referrer).hostname; } catch { return d.referrer.slice(0, 30); }
    }));
  }

  /** Click counts per browser, worked out from the User-Agent; largest first. */
  private browserCounts(): [string, number][] {
    return countBy(this.details().map((d) => browserName(d.userAgent)));
  }

  totalClicks(): number { return this.clicks().reduce((s, c) => s + c.count, 0); }
  uniqueIPs(): number { return new Set(this.details().map(d => d.ipAddress).filter(Boolean)).size; }
  avgDaily(): number { const c = this.clicks(); return c.length ? Math.round(this.totalClicks() / c.length) : 0; }
  topReferrer(): string {
    return this.referrerCounts()[0]?.[0] ?? '—';
  }

  private buildCharts(): void {
    this.destroyCharts();
    const isDark = document.documentElement.classList.contains('dark');
    const textColor = isDark ? '#94A3B8' : '#6B7280';
    const gridColor = isDark ? '#334155' : '#E5E7EB';

    const clickData = this.clicks().slice(-30);

    // Line chart
    const lc = this.lineChartRef()?.nativeElement;
    if (lc) {
      this.chartInstances.push(new Chart(lc, {
        type: 'line', data: {
          labels: clickData.map(c => c.clickDate),
          datasets: [{ data: clickData.map(c => c.count), borderColor: '#EE6123', backgroundColor: 'rgba(238,97,35,0.08)', fill: true, tension: 0.4, pointRadius: 3, pointBackgroundColor: '#EE6123', borderWidth: 2 }],
        },
        options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { x: { grid: { display: false }, ticks: { color: textColor } }, y: { beginAtZero: true, suggestedMax: 5, grid: { color: gridColor }, ticks: { precision: 0, color: textColor } } } },
      }));
    }

    // Doughnut
    const dc = this.doughnutChartRef()?.nativeElement;
    const referrers = topWithOther(this.referrerCounts(), 4);
    if (dc && referrers.length) {
      this.chartInstances.push(new Chart(dc, {
        type: 'doughnut', data: {
          labels: referrers.map(([name]) => name),
          datasets: [{ data: referrers.map(([, n]) => n), backgroundColor: ['#EE6123', '#2D4A7A', '#10B981', '#8B5CF6'], borderWidth: 0 }],
        },
        options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom', labels: { color: textColor, padding: 16 } } } },
      }));
    }

    // Horizontal bar
    const bc = this.barChartRef()?.nativeElement;
    const browsers = topWithOther(this.browserCounts(), 4);
    if (bc && browsers.length) {
      this.chartInstances.push(new Chart(bc, {
        type: 'bar', data: {
          labels: browsers.map(([name]) => name),
          datasets: [{ data: browsers.map(([, n]) => n), backgroundColor: ['#2D4A7A', '#EE6123', '#10B981', '#8B5CF6'], borderRadius: 6 }],
        },
        options: { indexAxis: 'y', responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, grid: { color: gridColor }, ticks: { precision: 0, color: textColor } }, y: { grid: { display: false }, ticks: { color: textColor } } } },
      }));
    }
  }

  private destroyCharts(): void { this.chartInstances.forEach(c => c.destroy()); this.chartInstances = []; }
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

