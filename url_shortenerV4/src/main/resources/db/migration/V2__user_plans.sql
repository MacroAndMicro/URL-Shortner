-- The plan a user subscribed to, and the end of the period they paid for.
-- A paid plan stays in effect for a 7-day grace period after plan_period_end,
-- then the user is treated as FREE (computed in PlanService).
ALTER TABLE users ADD COLUMN plan VARCHAR(20) NOT NULL DEFAULT 'FREE';
ALTER TABLE users ADD COLUMN plan_period_end TIMESTAMP(6);
