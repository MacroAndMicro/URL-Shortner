import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { PlanService } from '../../core/services/plan.service';
import { PLAN_LABELS, PlanName, describeDays, describeUsage } from '../../models/plan.model';

/** Shows the user's plan, what it allows, and how much of it is used. */
@Component({
  selector: 'app-plan-page',
  standalone: true,
  imports: [DatePipe],
  template: `
    <div class="section py-8 max-w-3xl">
      <h1 class="text-h2 mb-2 text-navy-900 dark:text-white">Your plan</h1>
      <p class="text-slate-500 dark:text-slate-400 mb-8">What your plan includes and how much of it you are using.</p>

      @if (planService.error()) {
        <div class="card text-red-600 dark:text-red-400">{{ planService.error() }}</div>
      }

      @if (planService.plan(); as p) {
        <div class="card-elevated mb-6">
          <div class="flex items-baseline justify-between gap-4 mb-6">
            <h2 class="text-h3 text-navy-900 dark:text-white">{{ labels[p.effectivePlan] }}</h2>
            @if (p.periodEnd && !p.inGracePeriod && p.effectivePlan !== 'FREE') {
              <span class="text-sm text-slate-500 dark:text-slate-400">Renews {{ p.periodEnd | date: 'mediumDate' }}</span>
            }
          </div>

          @if (p.inGracePeriod) {
            <div class="mb-6 px-4 py-3 rounded-lg bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 text-sm text-orange-800 dark:text-orange-300">
              Your payment was due on {{ p.periodEnd | date: 'mediumDate' }}. Pay by
              {{ p.graceEndsAt | date: 'mediumDate' }} to keep your {{ labels[p.subscribedPlan] }} plan;
              after that your account moves to Free.
            </div>
          }
          @if (p.paidLinksExpireAt) {
            <div class="mb-6 px-4 py-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-sm text-red-700 dark:text-red-400">
              Your {{ labels[p.subscribedPlan] }} plan lapsed on {{ p.graceEndsAt | date: 'mediumDate' }}, so Free plan rules apply.
              The links you made on {{ labels[p.subscribedPlan] }} stop working on
              {{ p.paidLinksExpireAt | date: 'medium' }} and cannot be recovered after that. Renew before then to keep them.
            </div>
          }

          <div class="mb-6">
            <div class="flex justify-between text-sm mb-1.5">
              <span class="font-semibold text-navy-900 dark:text-white">Links</span>
              <span class="text-slate-500 dark:text-slate-400">{{ usage(p) }}</span>
            </div>
            <div class="h-2 rounded-full bg-gray-100 dark:bg-slate-700 overflow-hidden"
                 role="progressbar" [attr.aria-label]="usage(p)" [attr.aria-valuenow]="p.linksUsed" aria-valuemin="0" [attr.aria-valuemax]="p.linkLimit">
              <div class="h-full bg-orange-500" [style.width.%]="usagePercent(p.linksUsed, p.linkLimit)"></div>
            </div>
            @if (p.limitPeriod !== 'ACTIVE' && p.periodEnd) {
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-1.5">Resets when your plan renews on {{ p.periodEnd | date: 'mediumDate' }}. Deleting a link does not give it back.</p>
            }
          </div>

          <dl class="grid sm:grid-cols-3 gap-4 text-sm">
            <div>
              <dt class="text-slate-500 dark:text-slate-400">Links last</dt>
              <dd class="font-semibold text-navy-900 dark:text-white">
                {{ p.linkLifetimeDays ? p.linkLifetimeDays + ' days' : 'While your plan is paid' }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-500 dark:text-slate-400">Change a link's destination</dt>
              <dd class="font-semibold text-navy-900 dark:text-white">
                {{ p.editWindowHours ? 'Within ' + p.editWindowHours + ' hours' : 'Any time' }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-500 dark:text-slate-400">Custom URLs</dt>
              <dd class="font-semibold text-navy-900 dark:text-white">{{ p.customCodes ? 'Included' : 'Yearly plan only' }}</dd>
            </div>
            <div>
              <dt class="text-slate-500 dark:text-slate-400">Click analytics</dt>
              <dd class="font-semibold text-navy-900 dark:text-white">{{ p.analytics ? 'Included' : 'Paid plans only' }}</dd>
            </div>
            <div>
              <dt class="text-slate-500 dark:text-slate-400">Individual clicks kept</dt>
              <dd class="font-semibold text-navy-900 dark:text-white">
                @if (p.analytics) { {{ days(p.clickDetailRetentionDays) }}, daily totals while the link exists } @else { — }
              </dd>
            </div>
            <div>
              <dt class="text-slate-500 dark:text-slate-400">Redirect type</dt>
              <dd class="font-semibold text-navy-900 dark:text-white">{{ p.redirectStatus }}</dd>
            </div>
          </dl>
        </div>

        @if (p.devPlanSwitchEnabled) {
          <div class="card border-dashed">
            <h2 class="text-sm font-semibold text-navy-900 dark:text-white mb-1">Development: switch plan</h2>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-4">
              Changes your plan without payment, to try the plan rules. Only available while the backend runs with
              DEV_PLAN_SWITCH_ENABLED=true.
            </p>
            <div class="flex flex-wrap gap-2">
              <button (click)="switchTo('FREE')" [disabled]="switching()" class="btn-outline text-sm">Free</button>
              <button (click)="switchTo('MONTHLY')" [disabled]="switching()" class="btn-outline text-sm">Monthly</button>
              <button (click)="switchTo('YEARLY')" [disabled]="switching()" class="btn-outline text-sm">Yearly</button>
              <button (click)="switchTo('MONTHLY', 3)" [disabled]="switching()" class="btn-outline text-sm">Monthly, payment 3 days late</button>
              <button (click)="switchTo('MONTHLY', 8)" [disabled]="switching()" class="btn-outline text-sm">Monthly, payment 8 days late</button>
            </div>
            @if (switchError()) {
              <p class="text-sm text-red-500 mt-3">{{ switchError() }}</p>
            }
          </div>
        }
      }
    </div>
  `,
})
export class PlanPageComponent implements OnInit {
  planService = inject(PlanService);
  labels = PLAN_LABELS;
  usage = describeUsage;
  days = describeDays;
  switching = signal(false);
  switchError = signal<string | null>(null);

  ngOnInit(): void {
    this.planService.fetchPlan();
  }

  usagePercent(used: number, max: number): number {
    return max > 0 ? Math.min(100, (used / max) * 100) : 0;
  }

  /** daysLate: make the paid period end that many days ago, to try the grace period. */
  switchTo(plan: PlanName, daysLate?: number): void {
    let periodEnd: string | undefined;
    if (daysLate) {
      const d = new Date(Date.now() - daysLate * 24 * 3600 * 1000);
      periodEnd = toLocalIso(d);
    }
    this.switching.set(true);
    this.switchError.set(null);
    this.planService.devChangePlan(plan, periodEnd).subscribe({
      next: () => this.switching.set(false),
      error: (err) => {
        this.switchError.set(err.error?.message ?? 'Could not change plan');
        this.switching.set(false);
      },
    });
  }
}

/** yyyy-MM-ddTHH:mm:ss in local time, the format the backend expects. */
function toLocalIso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
