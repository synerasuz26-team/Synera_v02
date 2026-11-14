-- P7: correlated outlet/partner visibility scanned all relationships for every candidate row.
CREATE INDEX outlet_npp_by_outlet_active
    ON outlet_npp(outlet_id,npp_tenant_id,effective_from,effective_to)
    WHERE status IN('ACTIVE','PENDING');
CREATE INDEX outlet_npp_by_distributor_active
    ON outlet_npp(npp_tenant_id,outlet_id,effective_from,effective_to)
    WHERE status IN('ACTIVE','PENDING');
CREATE INDEX tenant_relation_by_partners_active
    ON tenant_relation(nsx_tenant_id,npp_tenant_id,effective_from,effective_to)
    WHERE status='ACTIVE';
