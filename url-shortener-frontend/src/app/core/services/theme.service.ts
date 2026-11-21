import { Injectable, signal, effect } from '@angular/core';

const THEME_KEY = 'theme';

/** Light or dark theme: the saved choice, else the system setting. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<'light' | 'dark'>(this.saved() ?? this.getSystemPreference());

  constructor() {
    effect(() => {
      const t = this.theme();
      try {
        localStorage.setItem(THEME_KEY, t);
      } catch {
        // Storage blocked: the choice lasts for this page only.
      }
      document.documentElement.classList.toggle('dark', t === 'dark');
    });
  }

  toggle(): void {
    this.theme.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  isDark(): boolean {
    return this.theme() === 'dark';
  }

  private saved(): 'light' | 'dark' | null {
    try {
      const value = localStorage.getItem(THEME_KEY);
      return value === 'light' || value === 'dark' ? value : null;
    } catch {
      return null;
    }
  }

  private getSystemPreference(): 'light' | 'dark' {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
