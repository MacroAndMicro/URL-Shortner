import { Component, inject, OnInit, OnDestroy, ElementRef, viewChild, effect } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PlanService } from '../../core/services/plan.service';
import { PLAN_LABELS } from '../../models/plan.model';
import { Chart, ChartConfiguration, registerables } from 'chart.js';
import { AnalyticsService } from '../../core/services/analytics.service';
import { StatCardComponent } from '../../shared/components/stat-card.component';
import { SkeletonComponent } from '../../shared/components/skeleton.component';

Chart.register(...registerables);

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [RouterLink, DecimalPipe, DatePipe, StatCardComponent, SkeletonComponent],
  template: `
    <div class="section py-8">
      <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
        <div>
          <h1 class="text-h2 text-navy-900 dark:text-white">Dashboard</h1>
          <p class="text-slate-500 dark:text-slate-400 mt-1">Overview of your link performance.</p>
        </div>
        <a routerLink="/urls/create" class="btn-accent">+ Create New Link</a>
      </div>

      @if (planService.plan(); as p) {
        <div class="card rounded-2xl mb-8 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <p class="text-sm text-slate-500 dark:text-slate-400">{{ labels[p.effectivePlan] }} plan</p>
            <p class="text-lg font-semibold text-navy-900 dark:text-white">{{ p.activeUrls }} of {{ p.maxActiveUrls }} active links</p>
            @if (p.linkLifetimeDays) {
              <p class="text-sm text-slate-500 dark:text-slate-400">Links last {{ p.linkLifetimeDays }} days.</p>
            }
            @if (p.inGracePeriod) {
              <p class="text-sm text-orange-600 dark:text-orange-400">Payment overdue: pay by {{ p.graceEndsAt | date: 'mediumDate' }} to keep your plan.</p>
            }
          </div>
          <a routerLink="/plan" class="btn-outline text-sm">View plan</a>
        </div>

        @if (!p.analytics) {
          <div class="card-elevated text-center py-12">
            <h3 class="text-h3 text-navy-900 dark:text-white mb-2">Click analytics are on paid plans</h3>
            <p class="text-slate-500 dark:text-slate-400 mb-6">The Monthly and Yearly plans count every click and show where your visitors come from.</p>
            <a routerLink="/urls" class="btn-accent inline-block">Go to my links</a>
          </div>
        }
      }

      @if (!planService.plan()?.analytics) {
        <!-- Analytics not available on this plan (or the plan is still loading). -->
      } @else if (analytics.loading()) {
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
          @for (i of [1,2,3,4]; track i) {
            <div class="card rounded-2xl"><app-skeleton height="80px" /></div>
          }
        </div>
      } @else if (analytics.dashboard(); as d) {
        @if (d.totalUrls === 0) {
          <div class="card-elevated text-center py-16">
            <div class="text-5xl mb-4">🔗</div>
            <h3 class="text-h3 text-navy-900 dark:text-white mb-2">No URLs yet</h3>
            <p class="text-slate-500 dark:text-slate-400 mb-6">Create your first short link to start tracking analytics.</p>
            <a routerLink="/urls/create" class="btn-accent inline-block">Create Your First Link</a>
          </div>
        } @else {
          <!-- Stat cards -->
          <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
            <app-stat-card label="Total URLs" [value]="(d.totalUrls | number) ?? '0'" icon="🔗" accent="blue" />
            <app-stat-card label="Total Clicks" [value]="(d.totalClicks | number) ?? '0'" icon="👆" accent="orange" />
            <app-stat-card label="Clicks Today" [value]="(d.clicksToday | number) ?? '0'" icon="📅" accent="green" />
            <app-stat-card label="This Week" [value]="(d.clicksThisWeek | number) ?? '0'" icon="📈" accent="purple" />
          </div>

          <!-- Chart -->
          <div class="card rounded-2xl mb-8">
            <h3 class="text-h3 mb-4 text-navy-900 dark:text-white">Clicks Over Time</h3>
            <div class="h-64">
              <canvas #chartCanvas></canvas>
            </div>
          </div>

          <!-- Top URLs -->
          <div class="card rounded-2xl">
            <h3 class="text-h3 mb-4 text-navy-900 dark:text-white">Top Performing Links</h3>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b border-gray-100 dark:border-slate-700 text-left text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                    <th class="pb-3">Short URL</th><th class="pb-3 hidden sm:table-cell">Original</th><th class="pb-3 text-right">Clicks</th><th class="pb-3 text-right"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (url of d.topUrls; track url.id) {
                    <tr class="border-b border-gray-50 dark:border-slate-800 last:border-0 hover:bg-navy-50 dark:hover:bg-slate-800/50 transition-colors">
                      <td class="py-3 font-mono text-orange-500 font-medium text-xs">{{ url.shortUrl }}</td>
                      <td class="py-3 text-slate-500 dark:text-slate-400 max-w-[200px] truncate hidden sm:table-cell text-xs">{{ url.originalUrl }}</td>
                      <td class="py-3 text-right font-bold text-navy-900 dark:text-white">{{ url.clickCount | number }}</td>
                      <td class="py-3 text-right">
                        <a [routerLink]="['/analytics', url.shortUrl]" class="text-xs font-medium text-navy-500 dark:text-navy-200 hover:text-orange-500 transition-colors">View →</a>
                      </td>
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
export class DashboardPageComponent implements OnInit, OnDestroy {
  analytics = inject(AnalyticsService);
  planService = inject(PlanService);
  labels = PLAN_LABELS;
  chartCanvas = viewChild<ElementRef<HTMLCanvasElement>>('chartCanvas');
  private chart: Chart | null = null;

  constructor() {
    // Draw once the canvas exists, which is after the dashboard has loaded.
    effect(() => {
      if (this.chartCanvas()) {
        this.loadChart();
      }
    });
  }

  ngOnInit(): void {
    // Analytics endpoints refuse plans without analytics, so check first.
    this.planService.load().subscribe({
      next: (plan) => { if (plan.analytics) this.analytics.fetchDashboard(); },
      error: () => {},
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  /** Clicks per day for the last 7 days, across all of the user's links. */
  private loadChart(): void {
    const days = this.last7Days();
    this.analytics.getTotalClicks(days[0], days[6]).subscribe({
      next: (res) => {
        const byDate = new Map(res.data.map((d) => [d.clickDate, d.count]));
        this.buildChart(days, days.map((day) => byDate.get(day) ?? 0));
      },
      error: () => this.buildChart(days, days.map(() => 0)),
    });
  }

  private buildChart(days: string[], counts: number[]): void {
    const canvas = this.chartCanvas()?.nativeElement;
    if (!canvas) return;
    if (this.chart) this.chart.destroy();

    const isDark = document.documentElement.classList.contains('dark');
    const config: ChartConfiguration = {
      type: 'line',
      data: {
        labels: days.map((day) => new Date(day + 'T00:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric' })),
        datasets: [{
          label: 'Clicks',
          data: counts,
          borderColor: '#EE6123',
          backgroundColor: 'rgba(238,97,35,0.08)',
          fill: true,
          tension: 0.4,
          pointRadius: 4,
          pointBackgroundColor: '#EE6123',
          borderWidth: 2,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: isDark ? '#94A3B8' : '#6B7280' } },
          y: { grid: { color: isDark ? '#334155' : '#E5E7EB' }, ticks: { color: isDark ? '#94A3B8' : '#6B7280' } },
        },
      },
    };
    this.chart = new Chart(canvas, config);
  }

  /** The last 7 days as yyyy-MM-dd in local time, oldest first. */
  private last7Days(): string[] {
    const pad = (n: number) => String(n).padStart(2, '0');
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(); d.setDate(d.getDate() - (6 - i));
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    });
  }
}

