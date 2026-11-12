CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    tenant_type VARCHAR(16) NOT NULL CHECK (tenant_type IN ('PLATFORM', 'NSX', 'NPP', 'OUTLET')),
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE role (
    code VARCHAR(40) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    tenant_type VARCHAR(16) NOT NULL CHECK (tenant_type IN ('PLATFORM', 'NSX', 'NPP', 'OUTLET')),
    default_scope CHAR(1) NOT NULL CHECK (default_scope IN ('A', 'R', 'T', 'O')),
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE permission (
    code VARCHAR(80) PRIMARY KEY,
    module VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    phase VARCHAR(32) NOT NULL,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE role_permission (
    role_code VARCHAR(40) NOT NULL REFERENCES role(code),
    permission_code VARCHAR(80) NOT NULL REFERENCES permission(code),
    scope_code CHAR(1) NOT NULL CHECK (scope_code IN ('A', 'R', 'T', 'O')),
    PRIMARY KEY (role_code, permission_code)
);

ALTER TABLE app_users
    ADD COLUMN tenant_id UUID REFERENCES tenant(id),
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('INVITED', 'ACTIVE', 'LOCKED', 'DISABLED')),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX app_users_tenant_status_updated_idx ON app_users (tenant_id, status, updated_at DESC);

CREATE TABLE user_role (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    role_code VARCHAR(40) NOT NULL REFERENCES role(code),
    assigned_by UUID REFERENCES app_users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, user_id, role_code)
);

CREATE TABLE org_unit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    parent_id UUID REFERENCES org_unit(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    unit_type VARCHAR(32) NOT NULL,
    path TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);

CREATE TABLE user_org_unit (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, org_unit_id)
);

CREATE TABLE route (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    org_unit_id UUID REFERENCES org_unit(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);

CREATE TABLE user_route (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    route_id UUID NOT NULL REFERENCES route(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, route_id)
);

CREATE TABLE outlet (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    address TEXT NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, code)
);

CREATE TABLE route_outlet (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    route_id UUID NOT NULL REFERENCES route(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    position INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to DATE,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, route_id, outlet_id, effective_from)
);

CREATE TABLE mcp_schedule (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    route_id UUID NOT NULL REFERENCES route(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    week_of_month SMALLINT NOT NULL CHECK (week_of_month BETWEEN 1 AND 5),
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, route_id, outlet_id, week_of_month, day_of_week, effective_from)
);

CREATE TABLE visit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    route_id UUID REFERENCES route(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    sales_user_id UUID NOT NULL REFERENCES app_users(id),
    status VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    planned_at TIMESTAMPTZ,
    checked_in_at TIMESTAMPTZ,
    checked_out_at TIMESTAMPTZ,
    checkin_latitude NUMERIC(10, 7),
    checkin_longitude NUMERIC(10, 7),
    checkin_distance_m INTEGER CHECK (checkin_distance_m IS NULL OR checkin_distance_m >= 0),
    notes TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE visit_checklist_result (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    visit_id UUID NOT NULL REFERENCES visit(id),
    checklist_code VARCHAR(80) NOT NULL,
    result JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, visit_id, checklist_code)
);

CREATE TABLE attendance (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    attendance_date DATE NOT NULL,
    clock_in_at TIMESTAMPTZ,
    clock_out_at TIMESTAMPTZ,
    source VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, user_id, attendance_date)
);

CREATE TABLE tenant_relation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    nsx_tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (nsx_tenant_id <> npp_tenant_id),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, nsx_tenant_id, npp_tenant_id, effective_from)
);

CREATE TABLE contact (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID REFERENCES outlet(id),
    full_name VARCHAR(160) NOT NULL,
    phone VARCHAR(40) NOT NULL DEFAULT '',
    email VARCHAR(254) NOT NULL DEFAULT '',
    title VARCHAR(120) NOT NULL DEFAULT '',
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE outlet_npp (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to DATE,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, outlet_id, npp_tenant_id, effective_from)
);

CREATE TABLE interaction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    contact_id UUID REFERENCES contact(id),
    actor_id UUID NOT NULL REFERENCES app_users(id),
    interaction_type VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    sku VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    base_unit_code VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, sku)
);

CREATE TABLE unit_conversion (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    product_id UUID NOT NULL REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    unit_name VARCHAR(80) NOT NULL,
    base_units BIGINT NOT NULL CHECK (base_units > 0),
    is_base_unit BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (tenant_id, product_id, unit_code)
);

CREATE TABLE price_list (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'EFFECTIVE', 'REJECTED', 'EXPIRED')),
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, code)
);

CREATE TABLE price_list_scope (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    price_list_id UUID NOT NULL REFERENCES price_list(id),
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('ORG_UNIT', 'NPP', 'OUTLET')),
    scope_id UUID NOT NULL
);

CREATE TABLE price_list_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    price_list_id UUID NOT NULL REFERENCES price_list(id),
    product_id UUID NOT NULL REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    sellin_price_vnd BIGINT CHECK (sellin_price_vnd IS NULL OR sellin_price_vnd >= 0),
    sellout_price_vnd BIGINT CHECK (sellout_price_vnd IS NULL OR sellout_price_vnd >= 0),
    UNIQUE (tenant_id, price_list_id, product_id, unit_code)
);

CREATE TABLE promotion (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'EFFECTIVE', 'REJECTED', 'EXPIRED')),
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, code)
);

CREATE TABLE promotion_rule (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    promotion_id UUID NOT NULL REFERENCES promotion(id),
    product_id UUID REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    min_order_qty BIGINT CHECK (min_order_qty IS NULL OR min_order_qty >= 0),
    discount_basis_points INTEGER CHECK (discount_basis_points IS NULL OR discount_basis_points BETWEEN 0 AND 10000),
    gift_product_id UUID REFERENCES product(id),
    gift_qty BIGINT CHECK (gift_qty IS NULL OR gift_qty >= 0),
    rule_config JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE voucher (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'EFFECTIVE', 'REDEEMED', 'EXPIRED', 'CANCELLED')),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    discount_amount_vnd BIGINT CHECK (discount_amount_vnd IS NULL OR discount_amount_vnd >= 0),
    discount_basis_points INTEGER CHECK (discount_basis_points IS NULL OR discount_basis_points BETWEEN 0 AND 10000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);

CREATE TABLE order_status_transition (
    flow_type VARCHAR(16) NOT NULL CHECK (flow_type IN ('SELL_IN', 'SELL_OUT')),
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    action VARCHAR(80) NOT NULL,
    required_permission VARCHAR(80),
    actor VARCHAR(80) NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (flow_type, from_status, to_status)
);

CREATE TABLE order_si (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_code VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    note TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    UNIQUE (tenant_id, order_code),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE order_so (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    order_code VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, order_code),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE order_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_si_id UUID REFERENCES order_si(id),
    order_so_id UUID REFERENCES order_so(id),
    product_id UUID NOT NULL REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    entered_unit_code VARCHAR(32) NOT NULL,
    ordered_qty BIGINT NOT NULL CHECK (ordered_qty >= 0),
    confirmed_qty BIGINT NOT NULL DEFAULT 0 CHECK (confirmed_qty >= 0),
    shipped_qty BIGINT NOT NULL DEFAULT 0 CHECK (shipped_qty >= 0),
    received_qty BIGINT NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
    unit_price_vnd BIGINT NOT NULL DEFAULT 0 CHECK (unit_price_vnd >= 0),
    is_gift BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((order_si_id IS NOT NULL) <> (order_so_id IS NOT NULL)),
    CHECK (confirmed_qty <= ordered_qty),
    CHECK (shipped_qty <= confirmed_qty),
    CHECK (received_qty <= shipped_qty),
    CHECK (NOT is_gift OR unit_price_vnd = 0)
);

CREATE TABLE order_status_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_si_id UUID REFERENCES order_si(id),
    order_so_id UUID REFERENCES order_so(id),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id UUID REFERENCES app_users(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason TEXT NOT NULL DEFAULT '',
    CHECK ((order_si_id IS NOT NULL) <> (order_so_id IS NOT NULL))
);

CREATE TABLE return_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_so_id UUID REFERENCES order_so(id),
    order_si_id UUID REFERENCES order_si(id),
    request_code VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    reason TEXT NOT NULL,
    requested_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    CHECK ((order_si_id IS NOT NULL) <> (order_so_id IS NOT NULL)),
    CHECK (approved_by IS NULL OR approved_by <> requested_by),
    UNIQUE (tenant_id, request_code)
);

CREATE TABLE credit_limit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    limit_amount_vnd BIGINT CHECK (limit_amount_vnd IS NULL OR limit_amount_vnd >= 0),
    limit_qty BIGINT CHECK (limit_qty IS NULL OR limit_qty >= 0),
    unit_code VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'EFFECTIVE', 'REJECTED', 'EXPIRED')),
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (limit_amount_vnd IS NOT NULL OR (limit_qty IS NOT NULL AND unit_code IS NOT NULL)),
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

CREATE TABLE period (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    period_type VARCHAR(12) NOT NULL CHECK (period_type IN ('DAY', 'WEEK', 'MONTH', 'QUARTER')),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    closed_by UUID REFERENCES app_users(id),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ends_on >= starts_on),
    UNIQUE (tenant_id, period_type, starts_on, ends_on)
);

CREATE TABLE invoice (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_si_id UUID REFERENCES order_si(id),
    period_id UUID REFERENCES period(id),
    invoice_code VARCHAR(80) NOT NULL,
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd >= 0),
    issued_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, invoice_code)
);

CREATE TABLE payment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    invoice_id UUID REFERENCES invoice(id),
    period_id UUID REFERENCES period(id),
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd > 0),
    reference_code VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DECLARED',
    paid_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, reference_code)
);

CREATE TABLE deduction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    invoice_id UUID REFERENCES invoice(id),
    period_id UUID REFERENCES period(id),
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd >= 0),
    reason TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    period_id UUID REFERENCES period(id),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    ordered_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (ordered_amount_vnd >= 0),
    shipped_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (shipped_amount_vnd >= 0),
    received_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (received_amount_vnd >= 0),
    confirmed_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation_exception (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    reconciliation_id UUID NOT NULL REFERENCES reconciliation(id),
    exception_code VARCHAR(80) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_to UUID REFERENCES app_users(id),
    resolved_by UUID REFERENCES app_users(id),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE period_adjustment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    period_id UUID NOT NULL REFERENCES period(id),
    adjustment_code VARCHAR(80) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING_APPROVAL',
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    UNIQUE (tenant_id, adjustment_code)
);

CREATE TABLE inventory_npp (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    product_id UUID NOT NULL REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sellout_record (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID REFERENCES outlet(id),
    product_id UUID NOT NULL REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(24) NOT NULL,
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE demand_plan (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    product_id UUID NOT NULL REFERENCES product(id),
    unit_code VARCHAR(32) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    planned_for DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE target (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    target_code VARCHAR(80) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    subject_id UUID,
    period_id UUID REFERENCES period(id),
    target_qty BIGINT CHECK (target_qty IS NULL OR target_qty >= 0),
    target_amount_vnd BIGINT CHECK (target_amount_vnd IS NULL OR target_amount_vnd >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    UNIQUE (tenant_id, target_code)
);

CREATE TABLE ticket (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    ticket_code VARCHAR(80) NOT NULL,
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'NEW',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    outlet_id UUID REFERENCES outlet(id),
    order_si_id UUID REFERENCES order_si(id),
    order_so_id UUID REFERENCES order_so(id),
    created_by UUID NOT NULL REFERENCES app_users(id),
    assignee_id UUID REFERENCES app_users(id),
    due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, ticket_code)
);

CREATE TABLE ticket_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    ticket_id UUID NOT NULL REFERENCES ticket(id),
    actor_id UUID REFERENCES app_users(id),
    event_type VARCHAR(32) NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE trade_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    request_code VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    budget_vnd BIGINT CHECK (budget_vnd IS NULL OR budget_vnd >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    UNIQUE (tenant_id, request_code)
);

CREATE TABLE market_feedback (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID REFERENCES outlet(id),
    product_id UUID REFERENCES product(id),
    feedback_type VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE task (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    outlet_id UUID REFERENCES outlet(id),
    visit_id UUID REFERENCES visit(id),
    created_by UUID NOT NULL REFERENCES app_users(id),
    assignee_id UUID REFERENCES app_users(id),
    due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    recipient_id UUID NOT NULL REFERENCES app_users(id),
    notification_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE attachment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    owner_type VARCHAR(40) NOT NULL,
    owner_id UUID NOT NULL,
    storage_key TEXT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    uploaded_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    actor_id UUID REFERENCES app_users(id),
    permission_code VARCHAR(80) REFERENCES permission(code),
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID,
    old_values JSONB,
    new_values JSONB,
    reason TEXT NOT NULL DEFAULT '',
    ip_address INET,
    user_agent TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tenant_config (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    config_key VARCHAR(120) NOT NULL,
    config_value JSONB NOT NULL,
    updated_by UUID REFERENCES app_users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, config_key)
);

CREATE INDEX org_unit_tenant_path_idx ON org_unit (tenant_id, path text_pattern_ops);
CREATE INDEX org_unit_tenant_status_updated_idx ON org_unit (tenant_id, status, updated_at DESC);
CREATE INDEX route_tenant_status_updated_idx ON route (tenant_id, status, updated_at DESC);
CREATE INDEX route_outlet_tenant_outlet_idx ON route_outlet (tenant_id, outlet_id);
CREATE INDEX route_outlet_tenant_route_idx ON route_outlet (tenant_id, route_id);
CREATE INDEX mcp_schedule_tenant_route_idx ON mcp_schedule (tenant_id, route_id);
CREATE INDEX visit_tenant_status_updated_idx ON visit (tenant_id, status, updated_at DESC);
CREATE INDEX visit_tenant_outlet_idx ON visit (tenant_id, outlet_id);
CREATE INDEX visit_tenant_route_idx ON visit (tenant_id, route_id);
CREATE INDEX outlet_tenant_status_updated_idx ON outlet (tenant_id, status, updated_at DESC);
CREATE INDEX contact_tenant_outlet_idx ON contact (tenant_id, outlet_id);
CREATE INDEX outlet_npp_tenant_npp_idx ON outlet_npp (tenant_id, npp_tenant_id);
CREATE INDEX outlet_npp_tenant_outlet_idx ON outlet_npp (tenant_id, outlet_id);
CREATE INDEX product_tenant_status_updated_idx ON product (tenant_id, status, updated_at DESC);
CREATE INDEX price_list_tenant_status_updated_idx ON price_list (tenant_id, status, updated_at DESC);
CREATE INDEX price_list_scope_tenant_scope_idx ON price_list_scope (tenant_id, scope_type, scope_id);
CREATE INDEX promotion_tenant_status_updated_idx ON promotion (tenant_id, status, updated_at DESC);
CREATE INDEX order_si_tenant_status_updated_idx ON order_si (tenant_id, status, updated_at DESC);
CREATE INDEX order_si_tenant_npp_idx ON order_si (tenant_id, npp_tenant_id);
CREATE INDEX order_si_code_idx ON order_si (order_code);
CREATE INDEX order_so_tenant_status_updated_idx ON order_so (tenant_id, status, updated_at DESC);
CREATE INDEX order_so_tenant_outlet_idx ON order_so (tenant_id, outlet_id);
CREATE INDEX order_so_tenant_npp_idx ON order_so (tenant_id, npp_tenant_id);
CREATE INDEX order_so_code_idx ON order_so (order_code);
CREATE INDEX order_line_tenant_si_idx ON order_line (tenant_id, order_si_id);
CREATE INDEX order_line_tenant_so_idx ON order_line (tenant_id, order_so_id);
CREATE INDEX order_history_tenant_si_idx ON order_status_history (tenant_id, order_si_id);
CREATE INDEX order_history_tenant_so_idx ON order_status_history (tenant_id, order_so_id);
CREATE INDEX ticket_tenant_status_updated_idx ON ticket (tenant_id, status, updated_at DESC);
CREATE INDEX ticket_tenant_outlet_idx ON ticket (tenant_id, outlet_id);
CREATE INDEX inventory_npp_tenant_npp_idx ON inventory_npp (tenant_id, npp_tenant_id, recorded_at DESC);
CREATE INDEX inventory_npp_tenant_product_idx ON inventory_npp (tenant_id, product_id);
CREATE INDEX sellout_tenant_outlet_idx ON sellout_record (tenant_id, outlet_id);
CREATE INDEX sellout_tenant_npp_idx ON sellout_record (tenant_id, npp_tenant_id, occurred_at DESC);
CREATE INDEX task_tenant_status_updated_idx ON task (tenant_id, status, updated_at DESC);
CREATE INDEX audit_log_tenant_created_idx ON audit_log (tenant_id, created_at DESC);
CREATE INDEX refresh_token_tenant_user_idx ON refresh_token (tenant_id, user_id, expires_at);
CREATE INDEX notification_tenant_recipient_created_idx ON notification (tenant_id, recipient_id, created_at DESC);

CREATE FUNCTION enforce_order_status_transition() RETURNS trigger AS $$
DECLARE
    flow_name VARCHAR(16) := TG_ARGV[0];
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status
       AND NOT EXISTS (
           SELECT 1
           FROM order_status_transition transition
           WHERE transition.flow_type = flow_name
             AND transition.from_status = OLD.status
             AND transition.to_status = NEW.status
       ) THEN
        RAISE EXCEPTION 'Invalid % order status transition: % -> %', flow_name, OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_si_status_transition_guard
    BEFORE UPDATE OF status ON order_si
    FOR EACH ROW EXECUTE FUNCTION enforce_order_status_transition('SELL_IN');

CREATE TRIGGER order_so_status_transition_guard
    BEFORE UPDATE OF status ON order_so
    FOR EACH ROW EXECUTE FUNCTION enforce_order_status_transition('SELL_OUT');
