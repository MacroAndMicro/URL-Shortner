import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { DailyClickCount, ClickEventDetail, Dashboard, UrlMapping } from '../../models';
import { ApiResponse } from '../../models/api-response.model';

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/v1/urls`;

  readonly dashboard = signal<Dashboard | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  fetchDashboard(): void {
    this.loading.set(true);
    this.http.get<ApiResponse<Dashboard>>(`${this.baseUrl}/dashboard`).subscribe({
      next: (res) => {
        this.dashboard.set(res.data);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.message ?? 'Failed to load dashboard');
        this.loading.set(false);
      },
    });
  }

  getTopUrls(limit: number = 10, startDate?: string, endDate?: string): Observable<ApiResponse<UrlMapping[]>> {
    let params = new HttpParams().set('limit', limit.toString());
    if (startDate) params = params.set('startDate', startDate);
    if (endDate) params = params.set('endDate', endDate);
    return this.http.get<ApiResponse<UrlMapping[]>>(`${this.baseUrl}/top`, { params });
  }

  /** Clicks per day for one link; dates are yyyy-MM-ddTHH:mm:ss. */
  getAnalytics(shortUrl: string, startDate: string, endDate: string): Observable<ApiResponse<DailyClickCount[]>> {
    const params = new HttpParams()
      .set('shortUrl', shortUrl)
      .set('startDate', startDate)
      .set('endDate', endDate);
    return this.http.get<ApiResponse<DailyClickCount[]>>(`${this.baseUrl}/analytics`, { params });
  }

  getClickDetails(shortUrl: string, startDate: string, endDate: string): Observable<ApiResponse<ClickEventDetail[]>> {
    const params = new HttpParams()
      .set('startDate', startDate)
      .set('endDate', endDate);
    return this.http.get<ApiResponse<ClickEventDetail[]>>(`${this.baseUrl}/analytics/${shortUrl}/details`, { params });
  }

  getTotalClicks(startDate: string, endDate: string): Observable<ApiResponse<DailyClickCount[]>> {
    const params = new HttpParams()
      .set('startDate', startDate)
      .set('endDate', endDate);
    return this.http.get<ApiResponse<DailyClickCount[]>>(`${this.baseUrl}/totalClicks`, { params });
  }
}
