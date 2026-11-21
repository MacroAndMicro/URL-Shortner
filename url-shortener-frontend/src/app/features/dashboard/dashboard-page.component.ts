import { Component, inject, OnInit, AfterViewInit, ElementRef, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DecimalPipe } from '@angular/common';
import { Chart, ChartConfiguration, registerables } from 'chart.js';
import { AnalyticsService } from '../../core/services/analytics.service';
import { StatCardComponent } from '../../shared/components/stat-card.component';
import { SkeletonComponent } from '../../shared/components/skeleton.component';

Chart.register(...registerables);

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [RouterLink, DecimalPipe, StatCardComponent, SkeletonComponent],
  templateUrl: './dashboard-page.component.html',
  styleUrls: ['./dashboard-page.component.scss'],
  template: `
    <div class="section py-8">
      <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
        <div>
          <h1 class="text-h2 text-navy-900 dark:text-white">Dashboard</h1>
          <p class="text-slate-500 dark:text-slate-400 mt-1">Overview of your link performance.</p>
        </div>
        <a routerLink="/urls/create" class="btn-accent">+ Create New Link</a>
      </div>

      @if (analytics.loading()) {
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
export class DashboardPageComponent implements OnInit, AfterViewInit {
  analytics = inject(AnalyticsService);
  chartCanvas = viewChild<ElementRef<HTMLCanvasElement>>('chartCanvas');
  private chart: Chart | null = null;

  ngOnInit(): void {
    this.analytics.fetchDashboard();
  }

  ngAfterViewInit(): void {
    setTimeout(() => this.buildChart(), 800);
  }

  private buildChart(): void {
    const canvas = this.chartCanvas()?.nativeElement;
    if (!canvas) return;
    if (this.chart) this.chart.destroy();

    const isDark = document.documentElement.classList.contains('dark');
    const config: ChartConfiguration = {
      type: 'line',
      data: {
        labels: this.last7Days(),
        datasets: [{
          label: 'Clicks',
          data: [12, 19, 8, 27, 15, 33, 21],
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

  private last7Days(): string[] {
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(); d.setDate(d.getDate() - (6 - i));
      return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
    });
  }
}

