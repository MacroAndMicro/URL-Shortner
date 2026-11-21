export interface DailyClickCount {
  clickDate: string;
  count: number;
}

export interface ClickEventDetail {
  id: number;
  clickDate: string;
  ipAddress: string | null;
  referrer: string | null;
  userAgent: string | null;
}

export interface Dashboard {
  totalUrls: number;
  totalClicks: number;
  clicksToday: number;
  clicksThisWeek: number;
  topUrls: import('./url-mapping.model').UrlMapping[];
}
