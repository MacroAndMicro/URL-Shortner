import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

/**
 * Where the backend sends the browser after a Google/GitHub sign-in. Swaps
 * the one-time code in the URL for tokens, removes it from history, and
 * goes to the dashboard.
 */
@Component({
  selector: 'app-oauth-callback-page',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="section py-20">
      <div class="card-elevated mx-auto max-w-md text-center" aria-live="polite">
        @if (error()) {
          <h1 class="text-h3 text-navy-900 dark:text-white">Sign-in did not complete</h1>
          <p class="mt-2 text-slate-500 dark:text-slate-400">{{ error() }}</p>
          <a routerLink="/login" class="btn-accent mt-6">Back to sign in</a>
        } @else {
          <div class="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-orange-500" aria-hidden="true"></div>
          <p class="mt-4 text-slate-600 dark:text-slate-300">Signing you in…</p>
        }
      </div>
    </div>
  `,
})
export class OAuthCallbackPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  error = signal<string | null>(null);

  ngOnInit(): void {
    const code = this.route.snapshot.queryParamMap.get('code');
    // The code works once; keep it out of history either way.
    history.replaceState(null, '', '/auth/callback');
    if (!code) {
      this.error.set('The sign-in link is missing its code. Please try again.');
      return;
    }
    this.auth.exchangeOAuthCode(code).subscribe({
      next: () => this.router.navigate(['/dashboard'], { replaceUrl: true }),
      error: (err) => this.error.set(err.error?.message ?? 'Please try signing in again.'),
    });
  }
}
