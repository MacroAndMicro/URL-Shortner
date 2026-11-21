import { Routes } from '@angular/router';
import { LandingPageComponent } from './features/landing/landing-page.component';
import { LoginPageComponent } from './features/auth/login-page.component';
import { RegisterPageComponent } from './features/auth/register-page.component';
import { DashboardPageComponent } from './features/dashboard/dashboard-page.component';
import { CreateUrlPageComponent } from './features/urls/create-url-page.component';
import { MyUrlsPageComponent } from './features/urls/my-urls-page.component';
import { AnalyticsPageComponent } from './features/analytics/analytics-page.component';
import { PlanPageComponent } from './features/plan/plan-page.component';
import { authGuard, guestGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: '', component: LandingPageComponent },
  { path: 'login', component: LoginPageComponent, canActivate: [guestGuard] },
  { path: 'register', component: RegisterPageComponent, canActivate: [guestGuard] },
  { path: 'dashboard', component: DashboardPageComponent, canActivate: [authGuard] },
  { path: 'urls/create', component: CreateUrlPageComponent, canActivate: [authGuard] },
  { path: 'urls', component: MyUrlsPageComponent, canActivate: [authGuard] },
  { path: 'analytics/:shortUrl', component: AnalyticsPageComponent, canActivate: [authGuard] },
  { path: 'plan', component: PlanPageComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];

