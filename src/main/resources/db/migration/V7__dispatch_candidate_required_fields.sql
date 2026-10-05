-- SQL CHECK treats UNKNOWN as success. Explicit non-null checks close that gap for ranked rows.
ALTER TABLE dispatch_candidates DROP CONSTRAINT ck_dispatch_candidate_ranked;
ALTER TABLE dispatch_candidates ADD CONSTRAINT ck_dispatch_candidate_ranked CHECK (
    (reason = 'ELIGIBLE' AND score IS NOT NULL AND rank IS NOT NULL AND rank > 0
      AND straight_line_seconds IS NOT NULL AND straight_line_seconds >= 0
      AND distance_factor IS NOT NULL AND freshness_factor IS NOT NULL)
    OR (reason <> 'ELIGIBLE' AND score IS NULL AND rank IS NULL
      AND straight_line_seconds IS NULL AND distance_factor IS NULL
      AND freshness_factor IS NULL)
);
