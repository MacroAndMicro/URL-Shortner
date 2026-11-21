import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ShortenCardComponent } from '../../shared/components/shorten-card.component';

interface PlanSummary {
  name: string;
  price: string;
  highlight: boolean;
  points: string[];
}

@Component({
  selector: 'app-landing-page',
  standalone: true,
  imports: [RouterLink, ShortenCardComponent],
  template: `
    <!-- Shortener -->
    <section class="relative overflow-hidden bg-navy-900">
      <div class="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_85%_15%,rgba(238,97,35,0.28),transparent_45%),radial-gradient(circle_at_10%_90%,rgba(45,74,122,0.9),transparent_55%)]" aria-hidden="true"></div>
      <div class="section relative grid grid-cols-1 items-center gap-10 py-12 sm:py-16 lg:grid-cols-[minmax(0,32rem)_minmax(0,1fr)] lg:gap-16 lg:py-20">
        <app-shorten-card />

        <div class="text-white">
          <h1 class="text-display text-balance">
            Short links that are <span class="text-orange-400">simple to share</span>
          </h1>
          <p class="mt-5 max-w-xl text-lg leading-relaxed text-navy-100">
            Paste a long link, get a short one. Use it in messages, posts and print.
            Sign up to keep your links, and choose a plan to track every click.
          </p>
          <ul class="mt-8 space-y-3 text-navy-100">
            @for (point of heroPoints; track point) {
              <li class="flex items-start gap-3">
                <svg xmlns="http://www.w3.org/2000/svg" class="mt-0.5 h-5 w-5 text-orange-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7" /></svg>
                <span>{{ point }}</span>
              </li>
            }
          </ul>
          @if (!auth.isAuthenticated()) {
            <div class="mt-8 flex flex-wrap gap-3">
              <a routerLink="/register" class="btn-accent !px-6 !py-3">Sign up free</a>
              <a routerLink="/login" class="btn !border !border-white/30 !px-6 !py-3 text-white hover:bg-white/10">Log in</a>
            </div>
          }
        </div>
      </div>
    </section>

    <!-- How it works -->
    <section class="section-py">
      <h2 class="text-h2 text-center text-navy-900 dark:text-white">How it works</h2>
      <ol class="mx-auto mt-10 grid max-w-4xl grid-cols-1 gap-6 sm:grid-cols-3">
        @for (step of steps; track step.title; let i = $index) {
          <li class="card text-center">
            <span class="mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-orange-50 text-lg font-bold text-orange-600 dark:bg-orange-950/40 dark:text-orange-400">{{ i + 1 }}</span>
            <h3 class="text-h3 text-navy-900 dark:text-white">{{ step.title }}</h3>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ step.text }}</p>
          </li>
        }
      </ol>
    </section>

    <!-- Plans -->
    <section id="plans" class="border-t border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900/40">
      <div class="section-py">
        <h2 class="text-h2 text-center text-navy-900 dark:text-white">Plans</h2>
        <p class="mx-auto mt-3 max-w-xl text-center text-slate-500 dark:text-slate-400">
          Start without an account. Paid plans keep links for as long as you subscribe.
        </p>
        <div class="mt-10 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
          @for (plan of plans; track plan.name) {
            <div class="card flex flex-col" [class.ring-2]="plan.highlight" [class.ring-orange-500]="plan.highlight">
              <div class="flex items-center justify-between">
                <h3 class="text-h3 text-navy-900 dark:text-white">{{ plan.name }}</h3>
                @if (plan.highlight) {
                  <span class="rounded-full bg-orange-50 px-2 py-0.5 text-xs font-semibold text-orange-600 dark:bg-orange-950/40 dark:text-orange-400">Custom URLs</span>
                }
              </div>
              <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ plan.price }}</p>
              <ul class="mt-4 flex-1 space-y-2 text-sm text-slate-700 dark:text-slate-300">
                @for (point of plan.points; track point) {
                  <li class="flex items-start gap-2">
                    <svg xmlns="http://www.w3.org/2000/svg" class="mt-0.5 h-4 w-4 text-orange-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7" /></svg>
                    <span>{{ point }}</span>
                  </li>
                }
              </ul>
            </div>
          }
        </div>
        <p class="mt-6 text-center text-xs text-slate-500 dark:text-slate-400">
          Online payment for Monthly and Yearly is coming soon.
        </p>
      </div>
    </section>
  `,
})
export class LandingPageComponent {
  auth = inject(AuthService);

  heroPoints = [
    'One link without signing up',
    'Up to 50 links free with an account',
    'Click analytics on Monthly and Yearly',
    'Choose your own link on Yearly',
  ];

  steps = [
    { title: 'Paste', text: 'Enter the long link you want to share.' },
    { title: 'Shorten', text: 'Get a short link straight away.' },
    { title: 'Share', text: 'Copy it anywhere. Paid plans see every click.' },
  ];

  // Mirrors the backend's Plan enum; keep the two in step.
  plans: PlanSummary[] = [
    {
      name: 'Guest', price: 'No account', highlight: false,
      points: ['1 link at a time', 'Links last 5 days'],
    },
    {
      name: 'Free', price: 'Free account', highlight: false,
      points: ['50 links at a time', 'Links last 5 days', 'Change a link within 3 hours'],
    },
    {
      name: 'Monthly', price: 'Billed monthly', highlight: false,
      points: ['150 new links a month', 'Links last while subscribed', 'Click analytics, kept 3 months', 'Change links any time'],
    },
    {
      name: 'Yearly', price: 'Billed yearly', highlight: true,
      points: ['2000 new links a year', 'Links last while subscribed', 'Click analytics, kept 3 years', 'Choose your own link'],
    },
  ];
}
