import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  standalone: true,
  templateUrl: './empty-state.component.html',
  styleUrls: ['./empty-state.component.scss'],
})
export class EmptyStateComponent {
  title = input.required<string>();
  description = input.required<string>();
  actionLabel = input<string>('');
  actionClicked = output<void>();
}
