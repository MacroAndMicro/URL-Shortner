import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { StorageService } from './storage.service';
import { LoginRequest, RegisterRequest, LoginResponse, RefreshRequest, UserProfile } from '../../models/auth.model';
import { ApiResponse } from '../../models/api-response.model';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly storage = inject(StorageService);
  private readonly baseUrl = `${environment.apiUrl}/api/v1/auth`;

  readonly isAuthenticated = signal(this.storage.isAuthenticated());
  readonly currentUser = signal<UserProfile | null>(null);
  readonly authLoading = signal(false);
  readonly authError = signal<string | null>(null);

  login(request: LoginRequest): Observable<ApiResponse<LoginResponse>> {
    this.authLoading.set(true);
    this.authError.set(null);
    return this.http.post<ApiResponse<LoginResponse>>(`${this.baseUrl}/public/login`, request).pipe(
      tap((res) => {
        this.storage.setToken(res.data.token);
        this.storage.setRefreshToken(res.data.refreshToken);
        this.isAuthenticated.set(true);
        this.authLoading.set(false);
      }),
      catchError((err) => {
        this.authError.set(err.error?.message ?? 'Login failed');
        this.authLoading.set(false);
        return throwError(() => err);
      })
    );
  }

  register(request: RegisterRequest): Observable<ApiResponse<void>> {
    this.authLoading.set(true);
    this.authError.set(null);
    return this.http.post<ApiResponse<void>>(`${this.baseUrl}/public/register`, request).pipe(
      tap(() => {
        this.authLoading.set(false);
      }),
      catchError((err) => {
        this.authError.set(err.error?.message ?? 'Registration failed');
        this.authLoading.set(false);
        return throwError(() => err);
      })
    );
  }

  /** Sign-in providers the backend has set up, e.g. ['google', 'github']. */
  getOAuthProviders(): Observable<ApiResponse<string[]>> {
    return this.http.get<ApiResponse<string[]>>(`${this.baseUrl}/public/oauth/providers`);
  }

  /** Where the browser goes to sign in with a provider; the backend takes it from there. */
  oauthStartUrl(provider: string): string {
    return `${environment.apiUrl}/oauth2/authorization/${provider}`;
  }

  /** Exchanges the one-time code from a Google/GitHub sign-in for tokens, and signs in. */
  exchangeOAuthCode(code: string): Observable<ApiResponse<LoginResponse>> {
    return this.http.post<ApiResponse<LoginResponse>>(`${this.baseUrl}/public/oauth/exchange`, { code }).pipe(
      tap((res) => {
        this.storage.setToken(res.data.token);
        this.storage.setRefreshToken(res.data.refreshToken);
        this.isAuthenticated.set(true);
      })
    );
  }

  refreshToken(): Observable<ApiResponse<LoginResponse>> {
    const refreshToken = this.storage.getRefreshToken();
    const request: RefreshRequest = { refreshToken: refreshToken ?? '' };
    return this.http.post<ApiResponse<LoginResponse>>(`${this.baseUrl}/public/refresh`, request).pipe(
      tap((res) => {
        this.storage.setToken(res.data.token);
        this.storage.setRefreshToken(res.data.refreshToken);
        this.isAuthenticated.set(true);
      })
    );
  }

  fetchProfile(): Observable<ApiResponse<UserProfile>> {
    return this.http.get<ApiResponse<UserProfile>>(`${this.baseUrl}/me`).pipe(
      tap((res) => this.currentUser.set(res.data))
    );
  }

  logout(): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(`${this.baseUrl}/logout`, {}).pipe(
      tap(() => {
        this.storage.clear();
        this.isAuthenticated.set(false);
        this.currentUser.set(null);
        this.router.navigate(['/login']);
      })
    );
  }

  forceLogout(): void {
    this.storage.clear();
    this.isAuthenticated.set(false);
    this.currentUser.set(null);
    this.router.navigate(['/login']);
  }
}
