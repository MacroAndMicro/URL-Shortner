import { Component, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { UrlService } from '../../core/services/url.service';
import { CopyButtonComponent } from '../../shared/components/copy-button.component';
import { UrlMapping } from '../../models/url-mapping.model';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-create-url-page',
  standalone: true,
  imports: [ReactiveFormsModule, CopyButtonComponent],
  templateUrl: './create-url-page.component.html',
  styleUrls: ['./create-url-page.component.scss'],
  template: `
    <div class="section py-8">
      <h1 class="text-h2 mb-2 text-navy-900 dark:text-white">Create Short URL</h1>
      <p class="text-slate-500 dark:text-slate-400 mb-8">Paste a long URL and get an instant short link.</p>

      <div class="card-elevated max-w-2xl bg-gradient-to-br from-white dark:from-slate-850 to-navy-50 dark:to-slate-800">
        <form [formGroup]="form" (ngSubmit)="onSubmit()" class="space-y-4">
          <div>
            <label class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Long URL</label>
            <input type="url" formControlName="originalUrl" class="input-lg" placeholder="https://example.com/very/long/url/that-needs-shortening" />
            @if (form.get('originalUrl')?.invalid && form.get('originalUrl')?.touched) {
              <p class="text-sm text-red-500 mt-1">Please enter a valid URL starting with http:// or https://</p>
            }
          </div>

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
          </div>
        }
      </div>
    </div>
  `,
})
export class CreateUrlPageComponent {
  private readonly fb = inject(FormBuilder);
  urlService = inject(UrlService);

  form: FormGroup = this.fb.group({
    originalUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
  });

  createdUrl = signal<UrlMapping | null>(null);
  error = signal<string | null>(null);

  shortLink(): string {
    const url = this.createdUrl();
    return url ? `${environment.baseRedirectUrl}/${url.shortUrl}` : '';
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    this.urlService.createUrl(this.form.value).subscribe({
      next: (res) => { this.createdUrl.set(res.data); this.error.set(null); },
      error: (err) => { this.error.set(err.error?.message ?? 'Failed to shorten URL'); },
    });
  }
}

