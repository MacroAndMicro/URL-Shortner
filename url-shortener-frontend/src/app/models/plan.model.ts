export type PlanName = 'GUEST' | 'FREE' | 'MONTHLY' | 'YEARLY';

/** What linkLimit counts: working links at once, or links created per paid month / year. */
export type LimitPeriod = 'ACTIVE' | 'MONTH' | 'YEAR';

/** GET /api/v1/account/plan */
export interface PlanInfo {
  subscribedPlan: PlanName;
  /** The plan whose rules apply now: FREE once a paid plan's grace period has passed. */
  effectivePlan: PlanName;
  periodEnd: string | null;
  inGracePeriod: boolean;
  graceEndsAt: string | null;
  /** If a paid plan lapsed: when the links made while paid stop working for good. */
  paidLinksExpireAt: string | null;
  linkLimit: number;
  limitPeriod: LimitPeriod;
  linksUsed: number;
  /** Null while links last as long as the plan is paid. */
  linkLifetimeDays: number | null;
  /** Null when links can be edited any time. */
  editWindowHours: number | null;
  customCodes: boolean;
  analytics: boolean;
  clickDetailRetentionDays: number;
  redirectStatus: 301 | 302;
  devPlanSwitchEnabled: boolean;
}

export const PLAN_LABELS: Record<PlanName, string> = {
  GUEST: 'Guest',
  FREE: 'Free',
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly',
};

/** e.g. "12 of 50 working links" or "12 of 150 links created this month". */
export function describeUsage(p: PlanInfo): string {
  switch (p.limitPeriod) {
    case 'ACTIVE': return `${p.linksUsed} of ${p.linkLimit} working links`;
    case 'MONTH': return `${p.linksUsed} of ${p.linkLimit} links created this month`;
    case 'YEAR': return `${p.linksUsed} of ${p.linkLimit} links created this year`;
  }
}

/** e.g. "2 years" for 730 days. */
export function describeDays(days: number): string {
  if (days % 365 === 0) return days === 365 ? '1 year' : `${days / 365} years`;
  if (days % 30 === 0) return days === 30 ? '1 month' : `${days / 30} months`;
  return `${days} days`;
}
