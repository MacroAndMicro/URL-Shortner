import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';
import { PlanService } from '../../core/services/plan.service';
import { UrlService } from '../../core/services/url.service';
import { UrlMapping, CreateUrlRequest } from '../../models/url-mapping.model';
import { environment } from '../../environments/environment';
import { CopyButtonComponent } from './copy-button.component';

/**
 * The shortening form: long URL, optional custom URL (Yearly plan), and the
 * result with copy and open buttons. Works signed in or as a guest.
 */
@Component({
  selector: 'app-shorten-card',
  host: { class: 'block min-w-0' },
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DatePipe, CopyButtonComponent],
  template: `
    <div class="card-elevated text-left">
      @if (result(); as r) {
        <div class="animate-fade-up space-y-5" aria-live="polite">
          <div>
            <label for="sc-long" class="field-label">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" /></svg>
              Your long URL
            </label>
            <input id="sc-long" type="text" readonly class="input-field truncate" [value]="r.originalUrl" />
          </div>

          <div>
            <label for="sc-short" class="field-label">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-orange-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" /></svg>
              Your short link
            </label>
            <input id="sc-short" type="text" readonly class="input-lg font-semibold !text-orange-600 dark:!text-orange-400" [value]="shortLink()" />
            <div class="mt-3 flex flex-wrap gap-2">
              <app-copy-button [text]="shortLink()" label="Copy" />
              <a [href]="shortLink()" target="_blank" rel="noopener" class="btn-outline !py-1.5 !text-xs">Open link</a>
            </div>
            @if (r.expiresAt) {
              <p class="mt-3 text-sm text-slate-500 dark:text-slate-400">Works until {{ r.expiresAt | date: 'medium' }}.</p>
            }
          </div>

          <div class="flex flex-col gap-2 border-t border-slate-200 pt-5 sm:flex-row dark:border-slate-700">
            @if (auth.isAuthenticated()) {
              <a routerLink="/urls" class="btn-outline flex-1">My URLs</a>
            } @else {
              <a routerLink="/register" class="btn-outline flex-1">Sign up for more links</a>
            }
            <button type="button" (click)="reset()" class="btn-accent flex-1">Shorten another</button>
          </div>
        </div>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()" class="space-y-5" novalidate>
          <div>
            <label for="sc-url" class="field-label">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" /></svg>
              Shorten a long URL
            </label>
            <input id="sc-url" type="url" formControlName="originalUrl" class="input-lg"
                   placeholder="Enter long link here" autocomplete="url"
                   [attr.aria-invalid]="showUrlError()" aria-describedby="sc-url-error" />
            @if (showUrlError()) {
              <p id="sc-url-error" class="mt-1.5 text-sm text-red-600 dark:text-red-400">Enter a link that starts with http:// or https://</p>
            }
          </div>

          <div>
            <label for="sc-alias" class="field-label">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536M9 13l6.232-6.232a2.5 2.5 0 113.536 3.536L12.536 16.5H9V13z" /></svg>
              Customize your link
              @if (!canCustomize()) {
                <span class="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-400">Yearly plan</span>
              }
            </label>
            <div class="flex flex-col gap-2 sm:flex-row">
              <span class="input-field !w-auto shrink-0 select-none !bg-slate-50 !text-slate-500 dark:!bg-slate-800 dark:!text-slate-400">{{ host }}/</span>
              <input id="sc-alias" type="text" formControlName="customCode" class="input-field"
                     placeholder="Enter alias" autocomplete="off" aria-describedby="sc-alias-help" />
            </div>
            <p id="sc-alias-help" class="mt-1.5 text-xs text-slate-500 dark:text-slate-400">
              @if (canCustomize()) {
                Optional. 4 to 30 letters, digits or hyphens; not case sensitive.
              } @else if (auth.isAuthenticated()) {
                Choosing your own link is part of the Yearly plan. <a routerLink="/plan" class="font-medium text-orange-600 hover:underline dark:text-orange-400">See plans</a>
              } @else {
                Choosing your own link is part of the Yearly plan.
              }
            </p>
            @if (showAliasError()) {
              <p class="mt-1.5 text-sm text-red-600 dark:text-red-400">Use 4 to 30 letters, digits or hyphens, starting and ending with a letter or digit.</p>
            }
          </div>

          @if (error()) {
            <div class="alert-error" role="alert">
              {{ error() }}
              @if (!auth.isAuthenticated()) {
                <a routerLink="/register" class="mt-2 block font-semibold underline">Create a free account</a>
              }
            </div>
          }

          <button type="submit" [disabled]="loading()" class="btn-accent w-full !py-3.5 !text-base">
            @if (loading()) { Shortening… } @else { Shorten URL }
          </button>

          @if (!auth.isAuthenticated()) {
            <p class="text-center text-xs text-slate-500 dark:text-slate-400">
              No account needed for one link. Guest links last 5 days.
            </p>
          } @else if (planService.plan()?.editWindowHours; as hours) {
            <p class="text-center text-xs text-slate-500 dark:text-slate-400">
              You can change where a link points for {{ hours }} hours after creating it.
            </p>
          }
        </form>
      }
    </div>
  `,
})
export class ShortenCardComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly urlService = inject(UrlService);
  auth = inject(AuthService);
  planService = inject(PlanService);

  readonly host = environment.baseRedirectUrl.replace(/^https?:\/\//, '');

  form = this.fb.nonNullable.group({
    originalUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
    // Same rules as the backend's ShortCode.normalizeCustom, except reserved words.
    customCode: [{ value: '', disabled: true }, [Validators.pattern(/^$|^[A-Za-z0-9](?:[A-Za-z0-9-]{2,28})[A-Za-z0-9]$/)]],
  });

  loading = signal(false);
  error = signal<string | null>(null);
  result = signal<UrlMapping | null>(null);
  submitted = signal(false);

  canCustomize = computed(() => this.auth.isAuthenticated() && !!this.planService.plan()?.customCodes);

  ngOnInit(): void {
    if (this.auth.isAuthenticated()) {
      this.planService.load().subscribe({
        next: (plan) => { if (plan.customCodes) this.form.controls.customCode.enable(); },
        error: () => {},
      });
    }
  }

  shortLink(): string {
    const r = this.result();
    return r ? `${environment.baseRedirectUrl}/${r.shortUrl}` : '';
  }

  showUrlError(): boolean {
    const c = this.form.controls.originalUrl;
    return c.invalid && (c.touched || this.submitted());
  }

  showAliasError(): boolean {
    const c = this.form.controls.customCode;
    return c.enabled && c.invalid && (c.touched || this.submitted());
  }

  submit(): void {
    this.submitted.set(true);
    if (this.form.invalid) return;

    const originalUrl = this.form.controls.originalUrl.value.trim();
    const customCode = this.form.controls.customCode.enabled ? this.form.controls.customCode.value.trim() : '';
    const request: CreateUrlRequest = customCode ? { originalUrl, customCode } : { originalUrl };

    this.loading.set(true);
    this.error.set(null);
    const call = this.auth.isAuthenticated()
      ? this.urlService.createUrl(request)
      : this.urlService.createGuestUrl(request);
    call.subscribe({
      next: (res) => {
        this.result.set(res.data);
        this.loading.set(false);
        if (this.auth.isAuthenticated()) this.planService.fetchPlan(); // usage changed
      },
      error: (err) => {
        this.error.set(err.error?.message ?? 'Could not shorten the link. Please try again.');
        this.loading.set(false);
      },
    });
  }

  reset(): void {
    this.result.set(null);
    this.error.set(null);
    this.submitted.set(false);
    this.form.reset();
  }
}
