export type PlanName = 'GUEST' | 'FREE' | 'MONTHLY' | 'YEARLY';

/** GET /api/v1/account/plan */
export interface PlanInfo {
  subscribedPlan: PlanName;
  /** The plan whose rules apply now: FREE once a paid plan's grace period has passed. */
  effectivePlan: PlanName;
  periodEnd: string | null;
  inGracePeriod: boolean;
  graceEndsAt: string | null;
  maxActiveUrls: number;
  activeUrls: number;
  /** Null when links never expire. */
  linkLifetimeDays: number | null;
  analytics: boolean;
  redirectStatus: 301 | 302;
  devPlanSwitchEnabled: boolean;
}

export const PLAN_LABELS: Record<PlanName, string> = {
  GUEST: 'Guest',
  FREE: 'Free',
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly',
};
