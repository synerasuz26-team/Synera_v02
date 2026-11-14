-- Reconciliation filters by tenant, distributor and business-day range.
-- Keep the aggregation on eligible orders and live lines in the indexes.
CREATE INDEX order_si_reconciliation_range_idx
    ON order_si (tenant_id, npp_tenant_id, created_at, id)
    WHERE deleted_at IS NULL AND status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED');

CREATE INDEX order_line_reconciliation_amounts_idx
    ON order_line (tenant_id, order_si_id)
    INCLUDE (ordered_qty, shipped_qty, received_qty, line_amount_vnd)
    WHERE deleted_at IS NULL AND order_si_id IS NOT NULL;
