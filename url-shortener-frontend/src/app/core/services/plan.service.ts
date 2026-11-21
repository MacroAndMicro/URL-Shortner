import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApiResponse } from '../../models/api-response.model';
import { PlanInfo, PlanName } from '../../models/plan.model';

/** The signed-in user's plan and usage. */
@Injectable({ providedIn: 'root' })
export class PlanService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/v1/account/plan`;

  readonly plan = signal<PlanInfo | null>(null);
  readonly error = signal<string | null>(null);

  fetchPlan(): void {
    this.load().subscribe({ error: () => {} });
  }

  /** Loads the plan, updating the plan signal, and emits it. */
  load(): Observable<PlanInfo> {
    return this.http.get<ApiResponse<PlanInfo>>(this.baseUrl).pipe(
      map((res) => res.data),
      tap({
        next: (plan) => { this.plan.set(plan); this.error.set(null); },
        error: (err) => this.error.set(err.error?.message ?? 'Failed to load your plan'),
      })
    );
  }

  /**
   * Changes the plan without payment. Only works when the backend runs with
   * DEV_PLAN_SWITCH_ENABLED=true; for trying out the plan rules.
   */
  devChangePlan(plan: PlanName, periodEnd?: string): Observable<ApiResponse<PlanInfo>> {
    const body = periodEnd ? { plan, periodEnd } : { plan };
    return this.http.post<ApiResponse<PlanInfo>>(`${this.baseUrl}/dev`, body).pipe(
      tap((res) => this.plan.set(res.data))
    );
  }
}
