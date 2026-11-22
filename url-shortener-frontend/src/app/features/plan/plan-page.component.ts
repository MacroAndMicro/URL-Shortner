import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PlanService } from '../../core/services/plan.service';
import { BillingService, Payment, Price, Prices, formatPrice } from '../../core/services/billing.service';
import { PLAN_LABELS, PlanInfo, PlanName, describeDays, describeUsage } from '../../models/plan.model';

/** Days before the due date from which a plan can be renewed. */
const RENEW_FROM_DAYS = 7;

/** What a plan card's button does for this user. */
interface PayOption {
  price: Price;
  label: string;
  /** Why it can't be bought now; the button is disabled with this note. */
  blockedBecause: string | null;
  /** Short status line on the card, e.g. "Paid until 22 Dec 2026." */
  note: string | null;
}

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
              <button type="button" (click)="scrollToPay()" class="ml-1 font-semibold underline">Pay now</button>
            </div>
          }
          @if (p.paidLinksExpireAt) {
            <div class="mb-6 px-4 py-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-sm text-red-700 dark:text-red-400">
              Your {{ labels[p.subscribedPlan] }} plan lapsed on {{ p.graceEndsAt | date: 'mediumDate' }}, so Free plan rules apply.
              The links you made on {{ labels[p.subscribedPlan] }} stop working on
              {{ p.paidLinksExpireAt | date: 'medium' }} and cannot be recovered after that. Renew before then to keep them.
              <button type="button" (click)="scrollToPay()" class="ml-1 font-semibold underline">Pay now</button>
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

        <section id="pay" class="mb-6 scroll-mt-24" aria-labelledby="pay-heading">
          <h2 id="pay-heading" class="text-h3 text-navy-900 dark:text-white mb-1">Pay for a plan</h2>
          <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
            You pay once per month or year; nothing is charged automatically. We email you a few days before each payment is due.
          </p>

          @if (message(); as m) {
            <p class="mb-4" [class.alert-success]="m.kind === 'success'" [class.alert-error]="m.kind === 'error'"
               [class.alert-warning]="m.kind === 'info'" role="status">{{ m.text }}</p>
          }

          @if (prices(); as pr) {
            @if (!pr.paymentsEnabled) {
              <p class="alert-warning mb-4" role="status">Online payment is not set up on this server yet.</p>
            }
            <div class="grid sm:grid-cols-2 gap-4">
              @for (o of options(p, pr); track o.price.plan) {
                <div class="card flex flex-col" [class.ring-2]="o.price.plan === 'YEARLY'" [class.ring-orange-500]="o.price.plan === 'YEARLY'">
                  <div class="flex items-baseline justify-between gap-2">
                    <h3 class="font-semibold text-navy-900 dark:text-white">{{ labels[o.price.plan] }}</h3>
                    <p class="text-navy-900 dark:text-white">
                      <span class="text-2xl font-bold">{{ price(o.price.amountPaise, o.price.currency) }}</span>
                      <span class="text-sm text-slate-500 dark:text-slate-400"> / {{ o.price.period }}</span>
                    </p>
                  </div>
                  <p class="text-sm text-slate-500 dark:text-slate-400 mt-1 mb-4 flex-1">{{ o.note ?? blurb[o.price.plan] }}</p>
                  <button (click)="pay(o.price.plan)" class="btn-accent w-full"
                          [disabled]="!pr.paymentsEnabled || !!o.blockedBecause || paying() !== null">
                    {{ paying() === o.price.plan ? 'Opening payment…' : o.label }}
                  </button>
                  @if (o.blockedBecause) {
                    <p class="text-xs text-slate-500 dark:text-slate-400 mt-2">{{ o.blockedBecause }}</p>
                  }
                </div>
              }
            </div>
          } @else if (pricesError()) {
            <p class="alert-error" role="status">{{ pricesError() }}</p>
          }
        </section>

        @if (history().length) {
          <section class="card mb-6" aria-labelledby="history-heading">
            <h2 id="history-heading" class="text-sm font-semibold text-navy-900 dark:text-white mb-3">Payments</h2>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-left text-slate-500 dark:text-slate-400 border-b border-gray-100 dark:border-slate-700">
                    <th class="py-2 pr-4 font-medium">Date</th>
                    <th class="py-2 pr-4 font-medium">Plan</th>
                    <th class="py-2 pr-4 font-medium">Amount</th>
                    <th class="py-2 pr-4 font-medium">Status</th>
                    <th class="py-2 font-medium">Paid until</th>
                  </tr>
                </thead>
                <tbody>
                  @for (pay of history(); track pay.id) {
                    <tr class="border-b border-gray-50 dark:border-slate-800 last:border-0 text-navy-900 dark:text-slate-200">
                      <td class="py-2 pr-4 whitespace-nowrap">{{ (pay.paidDate ?? pay.createdDate) | date: 'mediumDate' }}</td>
                      <td class="py-2 pr-4">{{ labels[pay.plan] }}</td>
                      <td class="py-2 pr-4">{{ price(pay.amountPaise, pay.currency) }}</td>
                      <td class="py-2 pr-4">{{ pay.status === 'PAID' ? 'Paid' : 'Failed' }}</td>
                      <td class="py-2 whitespace-nowrap">{{ pay.periodEndAfter ? (pay.periodEndAfter | date: 'mediumDate') : '—' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

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
  private billing = inject(BillingService);
  private route = inject(ActivatedRoute);
  labels = PLAN_LABELS;
  price = formatPrice;
  blurb: Partial<Record<PlanName, string>> = {
    MONTHLY: '150 new links a month, click analytics kept 3 months.',
    YEARLY: '2000 new links a year, analytics kept 3 years, choose your own links.',
  };
  prices = signal<Prices | null>(null);
  pricesError = signal<string | null>(null);
  /** Completed payments; orders the user never paid are left out. */
  history = signal<Payment[]>([]);
  /** The plan whose payment window is opening or open. */
  paying = signal<PlanName | null>(null);
  message = signal<{ kind: 'success' | 'error' | 'info'; text: string } | null>(null);
  usage = describeUsage;
  days = describeDays;
  switching = signal(false);
  switchError = signal<string | null>(null);

  ngOnInit(): void {
    // Arriving from a "Pay now" link (/plan#pay): scroll once the section has rendered.
    const toPay = this.route.snapshot.fragment === 'pay';
    this.planService.load().subscribe({
      next: () => { if (toPay) setTimeout(() => this.scrollToPay()); },
      error: () => {},
    });
    this.billing.prices().subscribe({
      next: (p) => this.prices.set(p),
      error: () => this.pricesError.set('Could not load prices. Try again in a moment.'),
    });
    this.loadHistory();
  }

  scrollToPay(): void {
    document.getElementById('pay')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  private loadHistory(): void {
    this.billing.payments().subscribe({
      next: (h) => this.history.set(h.filter((x) => x.status !== 'CREATED')),
      error: () => {},
    });
  }

  /** The button and note for each paid plan, given the user's current plan. */
  options(p: PlanInfo, prices: Prices): PayOption[] {
    const paidNow = p.effectivePlan === 'MONTHLY' || p.effectivePlan === 'YEARLY' || p.inGracePeriod;
    const lapsed = !!p.paidLinksExpireAt;
    const end = p.periodEnd ? new Date(p.periodEnd) : null;
    const daysLeft = end ? (end.getTime() - Date.now()) / 86_400_000 : 0;
    const endText = end ? end.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '';

    return prices.plans.map((price): PayOption => {
      const amount = formatPrice(price.amountPaise, price.currency);
      const unit = price.period;
      if (paidNow && p.subscribedPlan === 'YEARLY' && price.plan === 'MONTHLY') {
        return { price, label: `Pay ${amount}`, note: null,
          blockedBecause: `You have Yearly until ${endText}. You can switch to Monthly when it ends.` };
      }
      if (p.subscribedPlan === price.plan && (paidNow || lapsed)) {
        const label = `Renew for ${amount}`;
        if (lapsed) {
          return { price, label, blockedBecause: null,
            note: `Your ${PLAN_LABELS[price.plan]} plan has lapsed. Renewing starts a new ${unit} today.` };
        }
        if (p.inGracePeriod) {
          return { price, label, blockedBecause: null,
            note: `Payment was due on ${endText}. Renewing continues from that date.` };
        }
        if (daysLeft <= RENEW_FROM_DAYS) {
          return { price, label, blockedBecause: null,
            note: `Paid until ${endText}. Renewing now adds a ${unit} after that.` };
        }
        return { price, label, note: `Paid until ${endText}.`,
          blockedBecause: `You can renew from ${RENEW_FROM_DAYS} days before it is due.` };
      }
      if (paidNow && p.subscribedPlan === 'MONTHLY' && price.plan === 'YEARLY') {
        return { price, label: `Switch to Yearly for ${amount}`, blockedBecause: null,
          note: `Yearly starts now; the year is added after your paid month ends on ${endText}, so you lose no days.` };
      }
      return { price, label: `Pay ${amount}`, note: null, blockedBecause: null };
    });
  }

  async pay(plan: PlanName): Promise<void> {
    this.paying.set(plan);
    this.message.set(null);
    try {
      const order = await firstValueFrom(this.billing.createOrder(plan));
      const result = await this.billing.openCheckout(order);
      if (!result) {
        this.message.set({ kind: 'info', text: 'Payment cancelled. You have not been charged.' });
        return;
      }
      const res = await firstValueFrom(this.billing.verify(result));
      this.message.set({ kind: 'success', text: res.message || 'Payment received. Thank you!' });
      this.planService.fetchPlan();
      this.loadHistory();
    } catch (err: any) {
      this.message.set({ kind: 'error', text: err?.error?.message ?? err?.message ?? 'The payment could not be completed.' });
    } finally {
      this.paying.set(null);
    }
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
