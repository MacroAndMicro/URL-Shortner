import { ChartConfiguration } from 'chart.js';

/**
 * One look for every chart. Each chart shows a single series, so it has no
 * legend: the card title names it. The series colour passed the palette
 * validator against both the light (#ffffff) and dark (#0f172a) surfaces.
 */
export const SERIES_COLOR = '#ee6123';

/** A short draw-in, or none for people who ask their system for reduced motion. */
function animation() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? false : { duration: 350 };
}

interface Ink {
  text: string;
  grid: string;
  tooltipBg: string;
  tooltipText: string;
  surface: string;
}

function ink(dark: boolean): Ink {
  return dark
    ? { text: '#94a3b8', grid: 'rgba(148, 163, 184, 0.14)', tooltipBg: '#f1f5f9', tooltipText: '#0f172a', surface: '#0f172a' }
    : { text: '#64748b', grid: 'rgba(100, 116, 139, 0.14)', tooltipBg: '#0f172a', tooltipText: '#f8fafc', surface: '#ffffff' };
}

function tooltip(c: Ink, unit: string) {
  return {
    backgroundColor: c.tooltipBg,
    titleColor: c.tooltipText,
    bodyColor: c.tooltipText,
    displayColors: false,
    padding: 10,
    cornerRadius: 6,
    callbacks: {
      label: (item: { parsed: { x: number; y: number } } & { chart: { config: { options?: { indexAxis?: string } } } }) => {
        const value = item.chart.config.options?.indexAxis === 'y' ? item.parsed.x : item.parsed.y;
        return `${value.toLocaleString()} ${value === 1 ? unit : unit + 's'}`;
      },
    },
  };
}

/** Clicks over time: a 2px line over a faint area, with a hover crosshair tooltip. */
export function lineChart(labels: string[], values: number[], dark: boolean): ChartConfiguration<'line'> {
  const c = ink(dark);
  return {
    type: 'line',
    data: {
      labels,
      datasets: [{
        label: 'Clicks',
        data: values,
        borderColor: SERIES_COLOR,
        backgroundColor: 'rgba(238, 97, 35, 0.10)',
        borderWidth: 2,
        fill: true,
        // Straight segments: each point is a whole day, so a curve would invent in-between values.
        tension: 0,
        pointRadius: values.length <= 31 ? 3 : 0,
        pointHoverRadius: 5,
        pointBackgroundColor: SERIES_COLOR,
        pointBorderColor: c.surface,
        pointBorderWidth: 2,
      }],
    },
    options: {
      animation: animation(),
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: { legend: { display: false }, tooltip: tooltip(c, 'click') as never },
      scales: {
        x: { grid: { display: false }, border: { display: false }, ticks: { color: c.text, maxRotation: 0, autoSkipPadding: 16 } },
        y: {
          beginAtZero: true,
          suggestedMax: 4,
          grid: { color: c.grid },
          border: { display: false },
          ticks: { color: c.text, precision: 0, maxTicksLimit: 5 },
        },
      },
    },
  };
}

/** A ranked list as horizontal bars; the data end is rounded, the baseline square. */
export function rankedBars(labels: string[], values: number[], dark: boolean): ChartConfiguration<'bar'> {
  const c = ink(dark);
  return {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        label: 'Clicks',
        data: values,
        backgroundColor: SERIES_COLOR,
        borderRadius: 4,
        borderSkipped: 'start',
        maxBarThickness: 18,
      }],
    },
    options: {
      indexAxis: 'y',
      animation: animation(),
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false }, tooltip: tooltip(c, 'click') as never },
      scales: {
        x: {
          beginAtZero: true,
          grid: { color: c.grid },
          border: { display: false },
          ticks: { color: c.text, precision: 0, maxTicksLimit: 5 },
        },
        y: { grid: { display: false }, border: { display: false }, ticks: { color: c.text } },
      },
    },
  };
}

/** yyyy-MM-dd in the browser's time zone. */
export function isoDay(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Every day from start to end inclusive, as yyyy-MM-dd. */
export function daysBetween(start: Date, end: Date): string[] {
  const days: string[] = [];
  const d = new Date(start.getFullYear(), start.getMonth(), start.getDate());
  const last = new Date(end.getFullYear(), end.getMonth(), end.getDate());
  while (d <= last) {
    days.push(isoDay(d));
    d.setDate(d.getDate() + 1);
  }
  return days;
}

/** Short axis label for a yyyy-MM-dd day, e.g. "Nov 21". */
export function dayLabel(day: string): string {
  return new Date(day + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
