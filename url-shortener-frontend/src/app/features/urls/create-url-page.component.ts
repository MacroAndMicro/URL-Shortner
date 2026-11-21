import { Component, inject } from '@angular/core';
import { PlanService } from '../../core/services/plan.service';
import { describeUsage } from '../../models/plan.model';
import { ShortenCardComponent } from '../../shared/components/shorten-card.component';

@Component({
  selector: 'app-create-url-page',
  standalone: true,
  imports: [ShortenCardComponent],
  template: `
    <div class="section max-w-2xl py-10">
      <h1 class="text-h2 text-navy-900 dark:text-white">Create a short link</h1>
      @if (planService.plan(); as p) {
        <p class="mt-1 mb-6 text-slate-500 dark:text-slate-400">{{ usage(p) }}</p>
      } @else {
        <p class="mt-1 mb-6 text-slate-500 dark:text-slate-400">Paste a long link and get a short one.</p>
      }
      <app-shorten-card />
    </div>
  `,
})
export class CreateUrlPageComponent {
  planService = inject(PlanService);
  usage = describeUsage;
}
