import { Component, OnInit, inject, input, signal } from '@angular/core';
import { AuthService } from '../../core/services/auth.service';

/**
 * "Continue with Google / GitHub" buttons. Shows only the providers the
 * backend has set up, and nothing at all if there are none.
 */
@Component({
  selector: 'app-social-sign-in',
  standalone: true,
  template: `
    @if (providers().length > 0) {
      <div class="space-y-3">
        @if (providers().includes('google')) {
          <a [href]="auth.oauthStartUrl('google')" class="btn-outline w-full !py-3">
            <svg class="h-5 w-5" viewBox="0 0 48 48" aria-hidden="true">
              <path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"/>
              <path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/>
              <path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"/>
              <path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C36.9 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"/>
            </svg>
            Continue with Google
          </a>
        }
        @if (providers().includes('github')) {
          <a [href]="auth.oauthStartUrl('github')" class="btn-outline w-full !py-3">
            <svg class="h-5 w-5 text-slate-900 dark:text-white" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 .5C5.7.5.5 5.7.5 12c0 5.1 3.3 9.4 7.9 10.9.6.1.8-.3.8-.6v-2c-3.2.7-3.9-1.5-3.9-1.5-.5-1.3-1.3-1.7-1.3-1.7-1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1 1.8 2.8 1.3 3.5 1 .1-.8.4-1.3.7-1.6-2.6-.3-5.3-1.3-5.3-5.7 0-1.3.5-2.3 1.2-3.1-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.4 11.4 0 0 1 6 0C17.4 4.6 18.4 5 18.4 5c.7 1.6.2 2.8.1 3.1.8.8 1.2 1.8 1.2 3.1 0 4.4-2.7 5.4-5.3 5.7.4.4.8 1.1.8 2.2v3.2c0 .3.2.7.8.6A11.5 11.5 0 0 0 23.5 12C23.5 5.7 18.3.5 12 .5z"/>
            </svg>
            Continue with GitHub
          </a>
        }
      </div>
      <div class="my-6 flex items-center gap-3 text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500" role="separator">
        <span class="h-px flex-1 bg-slate-200 dark:bg-slate-700"></span>
        or {{ separator() }}
        <span class="h-px flex-1 bg-slate-200 dark:bg-slate-700"></span>
      </div>
    }
  `,
})
export class SocialSignInComponent implements OnInit {
  auth = inject(AuthService);
  /** Text after "or", e.g. "with your username". */
  separator = input('with your username');
  providers = signal<string[]>([]);

  ngOnInit(): void {
    this.auth.getOAuthProviders().subscribe({
      next: (res) => this.providers.set(res.data),
      error: () => this.providers.set([]),
    });
  }
}
