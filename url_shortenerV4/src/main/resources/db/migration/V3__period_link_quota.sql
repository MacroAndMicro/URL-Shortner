-- Links a paid user has created in their current paid period, for the
-- per-period limits (150 per month on MONTHLY, 2000 per year on YEARLY).
-- period_links_start identifies the period being counted; when the period
-- moves on, the count starts again from zero.
ALTER TABLE users ADD COLUMN period_links_start TIMESTAMP(6);
ALTER TABLE users ADD COLUMN period_links_used INTEGER NOT NULL DEFAULT 0;
