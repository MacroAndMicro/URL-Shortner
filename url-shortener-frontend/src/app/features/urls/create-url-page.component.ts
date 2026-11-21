import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { UrlService } from '../../core/services/url.service';
import { PlanService } from '../../core/services/plan.service';
import { CopyButtonComponent } from '../../shared/components/copy-button.component';
import { UrlMapping } from '../../models/url-mapping.model';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-create-url-page',
  standalone: true,
  imports: [ReactiveFormsModule, DatePipe, CopyButtonComponent],
  template: `
    <div class="section py-8">
      <h1 class="text-h2 mb-2 text-navy-900 dark:text-white">Create Short URL</h1>
      <p class="text-slate-500 dark:text-slate-400 mb-8">Paste a long URL and get an instant short link.</p>

      <div class="card-elevated max-w-2xl bg-gradient-to-br from-white dark:from-slate-850 to-navy-50 dark:to-slate-800">
        <form [formGroup]="form" (ngSubmit)="onSubmit()" class="space-y-4">
          <div>
            <label for="original-url" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Long URL</label>
            <input id="original-url" type="url" formControlName="originalUrl" class="input-lg" placeholder="https://example.com/very/long/url/that-needs-shortening" />
            @if (form.get('originalUrl')?.invalid && form.get('originalUrl')?.touched) {
              <p class="text-sm text-red-500 mt-1">Please enter a valid URL starting with http:// or https://</p>
            }
          </div>

          @if (planService.plan()?.customCodes) {
            <div>
              <label for="custom-code" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">
                Custom URL <span class="font-normal text-slate-500 dark:text-slate-400">(optional)</span>
              </label>
              <div class="flex items-center gap-2">
                <span class="text-sm text-slate-500 dark:text-slate-400 whitespace-nowrap">{{ baseUrl }}/</span>
                <input id="custom-code" type="text" formControlName="customCode" class="input-field" placeholder="my-portfolio"
                       aria-describedby="custom-code-help" />
              </div>
              <p id="custom-code-help" class="text-xs text-slate-500 dark:text-slate-400 mt-1">
                4 to 30 letters, digits or hyphens. Not case sensitive. Leave empty for a generated code.
              </p>
              @if (form.get('customCode')?.invalid && form.get('customCode')?.touched) {
                <p class="text-sm text-red-500 mt-1">Use 4 to 30 letters, digits or hyphens, starting and ending with a letter or digit.</p>
              }
            </div>
          }

          @if (planService.plan()?.editWindowHours; as hours) {
            <p class="text-xs text-slate-500 dark:text-slate-400">
              On your plan you can change where a link points for {{ hours }} hours after creating it.
            </p>
          }

          <button type="submit" [disabled]="form.invalid || urlService.loading()" class="btn-accent w-full py-3 text-base">
            @if (urlService.loading()) { Shortening... } @else { Shorten URL }
          </button>
        </form>

        @if (error()) {
          <div class="mt-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-700 dark:text-red-400 px-4 py-3 rounded-lg text-sm">{{ error() }}</div>
        }

        @if (createdUrl()) {
          <div class="mt-6 p-5 bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 rounded-xl animate-slide-in-right">
            <p class="text-sm font-semibold text-green-700 dark:text-green-400 mb-3">✓ Short link created!</p>
            <div class="flex items-center gap-2">
              <code class="flex-1 bg-white dark:bg-slate-800 px-3 py-2.5 rounded-lg border border-green-200 dark:border-green-700 text-navy-900 dark:text-white font-mono text-sm break-all">{{ shortLink() }}</code>
              <app-copy-button [text]="shortLink()" label="Copy" />
            </div>
            @if (createdUrl()?.expiresAt; as expiresAt) {
              <p class="text-xs text-green-700 dark:text-green-400 mt-2">Works until {{ expiresAt | date: 'medium' }}.</p>
            }
          </div>
        }
      </div>
    </div>
  `,
})
export class CreateUrlPageComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  urlService = inject(UrlService);
  planService = inject(PlanService);
  baseUrl = environment.baseRedirectUrl;

  form: FormGroup = this.fb.group({
    originalUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
    // Same rules as the backend's ShortCode.normalizeCustom, except reserved words.
    customCode: ['', [Validators.pattern(/^$|^[A-Za-z0-9](?:[A-Za-z0-9-]{2,28})[A-Za-z0-9]$/)]],
  });

  ngOnInit(): void {
    this.planService.fetchPlan();
  }

  createdUrl = signal<UrlMapping | null>(null);
  error = signal<string | null>(null);

  shortLink(): string {
    const url = this.createdUrl();
    return url ? `${environment.baseRedirectUrl}/${url.shortUrl}` : '';
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    const { originalUrl, customCode } = this.form.value;
    const request = customCode?.trim() ? { originalUrl, customCode: customCode.trim() } : { originalUrl };
    this.urlService.createUrl(request).subscribe({
      next: (res) => {
        this.createdUrl.set(res.data);
        this.error.set(null);
        this.planService.fetchPlan(); // usage changed
      },
      error: (err) => { this.error.set(err.error?.message ?? 'Failed to shorten URL'); },
    });
  }
}

