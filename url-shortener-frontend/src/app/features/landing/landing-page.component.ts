import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';
import { UrlService } from '../../core/services/url.service';
import { UrlMapping } from '../../models/url-mapping.model';
import { CopyButtonComponent } from '../../shared/components/copy-button.component';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-landing-page',
  standalone: true,
  imports: [RouterLink, ReactiveFormsModule, DatePipe, CopyButtonComponent],
  template: `
    <!-- Hero -->
    <section class="relative overflow-hidden bg-gradient-to-br from-navy-900 via-navy-900 to-navy-500">
      <div class="absolute inset-0 bg-[radial-gradient(ellipse_at_top_right,rgba(238,97,35,0.15),transparent_50%)]"></div>
      <div class="relative section-py text-center">
        <h1 class="text-display text-white mb-6 text-balance">
          Short links,<br class="hidden sm:block" />
          <span class="text-orange-500">powerful</span> results
        </h1>
        <p class="text-body-lg max-w-2xl mx-auto mb-10 text-white/70">
          ShortLink is the link management platform for modern teams. Shorten URLs, track every click, and grow your brand.
        </p>

        <!-- Inline shortener -->
        <div class="max-w-xl mx-auto">
          <form [formGroup]="form" (ngSubmit)="shorten()" class="flex gap-2">
            <label for="landing-url" class="sr-only">Long URL</label>
            <input id="landing-url" type="url" formControlName="originalUrl" placeholder="Paste your long URL here..." class="input-lg flex-1" />
            <button type="submit" [disabled]="form.invalid || loading()" class="btn-accent px-6 py-4 whitespace-nowrap text-base">
              @if (loading()) { Shortening... } @else { Shorten }
            </button>
          </form>
          @if (!auth.isAuthenticated()) {
            <p class="mt-3 text-sm text-white/60">
              No account needed for one link. Links last 5 days.
              <a routerLink="/register" class="text-orange-400 font-medium">Sign up free</a> for more.
            </p>
          }
          @if (error()) {
            <div class="mt-4 p-4 bg-red-500/15 rounded-xl border border-red-400/40 text-left text-sm text-white">
              {{ error() }}
              @if (!auth.isAuthenticated()) {
                <div class="mt-3 flex gap-2">
                  <a routerLink="/register" class="btn-accent text-sm">Sign up free</a>
                  <a routerLink="/login" class="btn-ghost text-sm">Sign in</a>
                </div>
              }
            </div>
          }
          @if (result(); as r) {
            <div class="mt-4 p-4 bg-white/10 backdrop-blur rounded-xl border border-white/20 text-left animate-slide-in-right">
              <p class="text-xs text-white/50 mb-1">Your short link is ready:</p>
              <div class="flex items-center gap-2">
                <code class="flex-1 text-white font-mono text-sm">{{ shortLink() }}</code>
                <app-copy-button [text]="shortLink()" label="Copy" />
              </div>
              @if (r.expiresAt) {
                <p class="text-xs text-white/50 mt-2">Works until {{ r.expiresAt | date: 'medium' }}.</p>
              }
            </div>
          }
        </div>
      </div>
    </section>

    <!-- Trusted by -->
    <section class="section py-12 border-b border-gray-100 dark:border-slate-800">
      <p class="text-center text-sm font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-6">Trusted by teams everywhere</p>
      <div class="grid grid-cols-2 md:grid-cols-4 gap-6 text-center">
        <div><span class="text-3xl font-extrabold text-navy-900 dark:text-white">10M+</span><p class="text-xs text-slate-500 mt-1">Links Shortened</p></div>
        <div><span class="text-3xl font-extrabold text-navy-900 dark:text-white">500K+</span><p class="text-xs text-slate-500 mt-1">Active Users</p></div>
        <div><span class="text-3xl font-extrabold text-navy-900 dark:text-white">2B+</span><p class="text-xs text-slate-500 mt-1">Clicks Tracked</p></div>
        <div><span class="text-3xl font-extrabold text-navy-900 dark:text-white">99.9%</span><p class="text-xs text-slate-500 mt-1">Uptime</p></div>
      </div>
    </section>

    <!-- Features -->
    <section class="section-py">
      <h2 class="text-h2 text-center mb-4 text-navy-900 dark:text-white">Everything you need to manage links</h2>
      <p class="text-body-lg text-center mb-12 max-w-xl mx-auto">Powerful features to shorten, share, and track your links — all in one place.</p>
      <div class="grid md:grid-cols-2 lg:grid-cols-4 gap-6">
        @for (feat of features; track feat.title) {
          <div class="card-hover rounded-2xl text-center group">
            <div class="w-12 h-12 mx-auto mb-4 rounded-xl flex items-center justify-center text-2xl"
                 [class.bg-blue-50]="feat.color === 'blue'" [class.text-blue-600]="feat.color === 'blue'"
                 [class.bg-orange-50]="feat.color === 'orange'" [class.text-orange-500]="feat.color === 'orange'"
                 [class.bg-green-50]="feat.color === 'green'" [class.text-green-600]="feat.color === 'green'"
                 [class.bg-purple-50]="feat.color === 'purple'" [class.text-purple-600]="feat.color === 'purple'">
              {{ feat.icon }}
            </div>
            <h3 class="text-h3 mb-2 text-navy-900 dark:text-white">{{ feat.title }}</h3>
            <p class="text-sm text-slate-500 dark:text-slate-400">{{ feat.description }}</p>
          </div>
        }
      </div>
    </section>

    <!-- CTA -->
    <section class="section-py bg-navy-50 dark:bg-slate-850 rounded-2xl mb-16">
      <div class="text-center">
        <h2 class="text-h2 mb-4 text-navy-900 dark:text-white">Ready to get started?</h2>
        <p class="text-body-lg mb-8 max-w-lg mx-auto">Create your free account and start shortening URLs in seconds.</p>
        <a routerLink="/register" class="btn-accent px-8 py-4 text-base inline-block">Create Free Account</a>
      </div>
    </section>
  `,
})
export class LandingPageComponent {
  private readonly fb = inject(FormBuilder);
  private readonly urlService = inject(UrlService);
  auth = inject(AuthService);

  form: FormGroup = this.fb.group({
    originalUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
  });

  loading = signal(false);
  result = signal<UrlMapping | null>(null);
  error = signal<string | null>(null);

  features = [
    { icon: '🔗', title: 'Instant Shortening', description: 'Paste a long URL and get a short, shareable link in one click.', color: 'blue' },
    { icon: '📊', title: 'Powerful Analytics', description: 'Track clicks, referrers, and geography for every link you create.', color: 'orange' },
    { icon: '🛡️', title: 'Secure & Private', description: 'Enterprise-grade security with JWT auth. Your data stays yours.', color: 'green' },
    { icon: '⚡', title: 'Lightning Fast', description: 'Global redirects in under 50ms. Your audience never waits.', color: 'purple' },
  ];

  shortLink(): string {
    const r = this.result();
    return r ? `${environment.baseRedirectUrl}/${r.shortUrl}` : '';
  }

  shorten(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const request = this.auth.isAuthenticated()
      ? this.urlService.createUrl(this.form.value)
      : this.urlService.createGuestUrl(this.form.value);
    request.subscribe({
      next: (res) => { this.result.set(res.data); this.loading.set(false); },
      error: (err) => {
        this.result.set(null);
        this.error.set(err.error?.message ?? 'Could not shorten the URL. Please try again.');
        this.loading.set(false);
      },
    });
  }
}

