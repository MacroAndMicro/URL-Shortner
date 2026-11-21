import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [RouterLink, ReactiveFormsModule],
  template: `
    <div class="section py-16">
      <div class="card-elevated max-w-md mx-auto">
        <h1 class="text-h2 mb-6 text-navy-900 dark:text-white">Sign in</h1>
        <form [formGroup]="form" (ngSubmit)="onSubmit()" class="space-y-4">
          <div>
            <label for="username" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Username</label>
            <input id="username" type="text" formControlName="username" autocomplete="username" class="input-field" />
          </div>
          <div>
            <label for="password" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Password</label>
            <input id="password" type="password" formControlName="password" autocomplete="current-password" class="input-field" />
          </div>
          @if (auth.authError()) {
            <p class="text-sm text-red-500">{{ auth.authError() }}</p>
          }
          <button type="submit" [disabled]="form.invalid || auth.authLoading()" class="btn-accent w-full py-3">
            @if (auth.authLoading()) { Signing in... } @else { Sign in }
          </button>
        </form>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-6 text-center">
          No account? <a routerLink="/register" class="text-orange-500 font-medium">Sign up free</a>
        </p>
      </div>
    </div>
  `,
})
export class LoginPageComponent {
  private readonly fb = inject(FormBuilder);
  auth = inject(AuthService);
  private readonly router = inject(Router);

  form: FormGroup = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  onSubmit(): void {
    if (this.form.invalid) return;
    this.auth.login(this.form.value).subscribe({
      next: () => this.router.navigate(['/dashboard']),
    });
  }
}
