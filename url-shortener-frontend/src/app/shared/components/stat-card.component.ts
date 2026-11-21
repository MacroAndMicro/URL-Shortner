import { Component, input } from '@angular/core';

@Component({
  selector: 'app-stat-card',
  standalone: true,
  templateUrl: './stat-card.component.html',
  styleUrls: ['./stat-card.component.scss'],
})
export class StatCardComponent {
  label = input.required<string>();
  value = input.required<string | number>();
  icon = input<string>('');
  comparison = input<string>('');
  comparisonTrend = input<'up' | 'down'>('up');
  accent = input<'blue' | 'orange' | 'green' | 'purple'>('blue');
}
