import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { UrlMapping, CreateUrlRequest, UpdateUrlRequest } from '../../models/url-mapping.model';
import { ApiResponse, CursorPage } from '../../models/api-response.model';

@Injectable({ providedIn: 'root' })
export class UrlService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/v1/urls`;

  readonly urls = signal<UrlMapping[]>([]);
  readonly pagination = signal<CursorPage<UrlMapping> | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  createUrl(request: CreateUrlRequest): Observable<ApiResponse<UrlMapping>> {
    this.loading.set(true);
    return this.http.post<ApiResponse<UrlMapping>>(`${this.baseUrl}/shorten`, request).pipe(
      tap({
        next: () => this.loading.set(false),
        error: () => this.loading.set(false),
      })
    );
  }

  getMyUrls(cursor: string | null = null, size: number = 20, direction: string = 'desc'): void {
    this.loading.set(true);
    this.error.set(null);
    let params = new HttpParams()
      .set('size', size.toString())
      .set('direction', direction);
    if (cursor) {
      params = params.set('cursor', cursor);
    }

    this.http.get<ApiResponse<CursorPage<UrlMapping>>>(`${this.baseUrl}/myurls`, { params }).subscribe({
      next: (res) => {
        this.urls.set(res.data.content);
        this.pagination.set(res.data);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.message ?? 'Failed to load URLs');
        this.loading.set(false);
      },
    });
  }

  getUrlById(id: string): Observable<ApiResponse<UrlMapping>> {
    return this.http.get<ApiResponse<UrlMapping>>(`${this.baseUrl}/${id}`);
  }

  updateUrl(id: string, request: UpdateUrlRequest): Observable<ApiResponse<UrlMapping>> {
    return this.http.patch<ApiResponse<UrlMapping>>(`${this.baseUrl}/${id}`, request);
  }

  deleteUrl(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.baseUrl}/${id}`);
  }
}
