DO $$
DECLARE
    status_check_definition TEXT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'product'::regclass AND conname = 'product_status_check'
    ) THEN
        ALTER TABLE product
            ADD CONSTRAINT product_status_check
                CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'));
    END IF;

    SELECT pg_get_constraintdef(oid) INTO status_check_definition
    FROM pg_constraint
    WHERE conrelid = 'price_list'::regclass AND conname = 'price_list_status_check';
    IF status_check_definition IS NOT NULL
       AND position('APPROVED' IN status_check_definition) = 0 THEN
        ALTER TABLE price_list DROP CONSTRAINT price_list_status_check;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'price_list'::regclass AND conname = 'price_list_status_check'
    ) THEN
        ALTER TABLE price_list
            ADD CONSTRAINT price_list_status_check
                CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'EFFECTIVE', 'REJECTED', 'EXPIRED'));
    END IF;

    SELECT pg_get_constraintdef(oid) INTO status_check_definition
    FROM pg_constraint
    WHERE conrelid = 'promotion'::regclass AND conname = 'promotion_status_check';
    IF status_check_definition IS NOT NULL
       AND position('APPROVED' IN status_check_definition) = 0 THEN
        ALTER TABLE promotion DROP CONSTRAINT promotion_status_check;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'promotion'::regclass AND conname = 'promotion_status_check'
    ) THEN
        ALTER TABLE promotion
            ADD CONSTRAINT promotion_status_check
                CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'EFFECTIVE', 'REJECTED', 'EXPIRED'));
    END IF;

    ALTER TABLE voucher
        ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES app_users(id),
        ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES app_users(id);

    SELECT pg_get_constraintdef(oid) INTO status_check_definition
    FROM pg_constraint
    WHERE conrelid = 'voucher'::regclass AND conname = 'voucher_status_check';
    IF status_check_definition IS NOT NULL
       AND position('APPROVED' IN status_check_definition) = 0 THEN
        ALTER TABLE voucher DROP CONSTRAINT voucher_status_check;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'voucher'::regclass AND conname = 'voucher_status_check'
    ) THEN
        ALTER TABLE voucher
            ADD CONSTRAINT voucher_status_check
                CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'EFFECTIVE',
                    'REDEEMED', 'EXPIRED', 'CANCELLED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'voucher'::regclass AND conname = 'voucher_approver_differs_from_creator'
    ) THEN
        ALTER TABLE voucher
            ADD CONSTRAINT voucher_approver_differs_from_creator
                CHECK (approved_by IS NULL OR approved_by <> created_by);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'voucher'::regclass AND conname = 'voucher_exactly_one_discount'
    ) THEN
        ALTER TABLE voucher
            ADD CONSTRAINT voucher_exactly_one_discount
                CHECK ((discount_amount_vnd IS NOT NULL) <> (discount_basis_points IS NOT NULL));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'price_list_line'::regclass AND conname = 'price_list_line_has_price'
    ) THEN
        ALTER TABLE price_list_line
            ADD CONSTRAINT price_list_line_has_price
                CHECK (sellin_price_vnd IS NOT NULL OR sellout_price_vnd IS NOT NULL);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS unit_conversion_one_base_unit_idx
    ON unit_conversion (tenant_id, product_id)
    WHERE is_base_unit;

CREATE UNIQUE INDEX IF NOT EXISTS price_list_scope_unique_idx
    ON price_list_scope (tenant_id, price_list_id, scope_type, scope_id);

CREATE TABLE IF NOT EXISTS promotion_scope (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    promotion_id UUID NOT NULL REFERENCES promotion(id),
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('ORG_UNIT', 'NPP', 'OUTLET')),
    scope_id UUID NOT NULL,
    UNIQUE (tenant_id, promotion_id, scope_type, scope_id)
);

CREATE TABLE IF NOT EXISTS voucher_scope (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    voucher_id UUID NOT NULL REFERENCES voucher(id),
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('ORG_UNIT', 'NPP', 'OUTLET')),
    scope_id UUID NOT NULL,
    UNIQUE (tenant_id, voucher_id, scope_type, scope_id)
);

CREATE INDEX IF NOT EXISTS promotion_tenant_status_effective_idx
    ON promotion (tenant_id, status, effective_from, effective_to);
CREATE INDEX IF NOT EXISTS promotion_scope_tenant_scope_idx
    ON promotion_scope (tenant_id, scope_type, scope_id);
CREATE INDEX IF NOT EXISTS voucher_tenant_status_effective_idx
    ON voucher (tenant_id, status, effective_from, effective_to);
CREATE INDEX IF NOT EXISTS voucher_scope_tenant_scope_idx
    ON voucher_scope (tenant_id, scope_type, scope_id);
