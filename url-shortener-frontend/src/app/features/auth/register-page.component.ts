import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';
import { SocialSignInComponent } from '../../shared/components/social-sign-in.component';

@Component({
  selector: 'app-register-page',
  standalone: true,
  imports: [RouterLink, ReactiveFormsModule, SocialSignInComponent],
  template: `
    <div class="section py-16">
      <div class="card-elevated max-w-md mx-auto">
        <h1 class="text-h2 mb-6 text-navy-900 dark:text-white">Create your account</h1>
        <app-social-sign-in separator="sign up with email" />
        <form [formGroup]="form" (ngSubmit)="onSubmit()" class="space-y-4">
          <div>
            <label for="username" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Username</label>
            <input id="username" type="text" formControlName="username" autocomplete="username" class="input-field" />
            @if (form.get('username')?.invalid && form.get('username')?.touched) {
              <p class="text-sm text-red-500 mt-1">5 to 50 characters.</p>
            }
          </div>
          <div>
            <label for="email" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Email</label>
            <input id="email" type="email" formControlName="email" autocomplete="email" class="input-field" />
            @if (form.get('email')?.invalid && form.get('email')?.touched) {
              <p class="text-sm text-red-500 mt-1">Enter a valid email address.</p>
            }
          </div>
          <div>
            <label for="password" class="block text-sm font-semibold text-navy-900 dark:text-white mb-1.5">Password</label>
            <input id="password" type="password" formControlName="password" autocomplete="new-password" class="input-field" />
            @if (form.get('password')?.invalid && form.get('password')?.touched) {
              <p class="text-sm text-red-500 mt-1">At least 8 characters.</p>
            }
          </div>
          @if (auth.authError()) {
            <p class="text-sm text-red-500">{{ auth.authError() }}</p>
          }
          <button type="submit" [disabled]="form.invalid || auth.authLoading()" class="btn-accent w-full py-3">
            @if (auth.authLoading()) { Creating account... } @else { Sign up }
          </button>
        </form>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-6 text-center">
          Already have an account? <a routerLink="/login" class="text-orange-500 font-medium">Sign in</a>
        </p>
      </div>
    </div>
  `,
})
export class RegisterPageComponent {
  private readonly fb = inject(FormBuilder);
  auth = inject(AuthService);
  private readonly router = inject(Router);

  form: FormGroup = this.fb.group({
    username: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(50)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  onSubmit(): void {
    if (this.form.invalid) return;
    this.auth.register(this.form.value).subscribe({
      next: () => this.router.navigate(['/login']),
    });
  }
}

