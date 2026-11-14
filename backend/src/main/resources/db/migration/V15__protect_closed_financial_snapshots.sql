CREATE FUNCTION protect_closed_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_closed BOOLEAN := false; new_closed BOOLEAN := false;
BEGIN
 IF TG_OP<>'INSERT' THEN
   SELECT p.status='CLOSED' AND NOT t.is_demo INTO old_closed FROM period p JOIN tenant t ON t.id=p.tenant_id WHERE p.id=OLD.period_id;
 END IF;
 IF TG_OP<>'DELETE' THEN
   SELECT p.status='CLOSED' AND NOT t.is_demo INTO new_closed FROM period p JOIN tenant t ON t.id=p.tenant_id WHERE p.id=NEW.period_id;
 END IF;
 IF COALESCE(old_closed,false) OR COALESCE(new_closed,false) THEN
   RAISE EXCEPTION 'Closed reconciliation snapshot is immutable; use an independently approved adjustment.' USING ERRCODE='23514',CONSTRAINT='closed_reconciliation_immutable';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER closed_reconciliation_immutable BEFORE INSERT OR UPDATE OR DELETE ON reconciliation FOR EACH ROW EXECUTE FUNCTION protect_closed_reconciliation();

CREATE FUNCTION protect_approved_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE demo BOOLEAN;
BEGIN
 IF TG_OP='DELETE' THEN SELECT is_demo INTO demo FROM tenant WHERE id=OLD.tenant_id;
 ELSE SELECT is_demo INTO demo FROM tenant WHERE id=NEW.tenant_id; END IF;
 IF NOT COALESCE(demo,false) THEN
   IF TG_OP<>'INSERT' AND OLD.status='APPROVED' THEN
     RAISE EXCEPTION 'Approved adjustment is immutable.' USING ERRCODE='23514',CONSTRAINT='approved_adjustment_immutable';
   END IF;
   IF TG_OP<>'DELETE' AND NEW.status='APPROVED' AND (NEW.approved_by IS NULL OR NEW.approved_by=NEW.created_by OR NOT EXISTS(SELECT 1 FROM period WHERE id=NEW.period_id AND tenant_id=NEW.tenant_id AND status='CLOSED')) THEN
     RAISE EXCEPTION 'Adjustment needs a different approver and a closed period.' USING ERRCODE='23514',CONSTRAINT='adjustment_maker_checker';
   END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER approved_adjustment_immutable BEFORE INSERT OR UPDATE OR DELETE ON period_adjustment FOR EACH ROW EXECUTE FUNCTION protect_approved_adjustment();

CREATE FUNCTION protect_closed_period() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='CLOSED' AND NOT EXISTS(SELECT 1 FROM tenant WHERE id=OLD.tenant_id AND is_demo) THEN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Closed period cannot be deleted.' USING ERRCODE='23514'; END IF;
   IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Closed period cannot be rewritten.' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER closed_period_immutable BEFORE UPDATE OR DELETE ON period FOR EACH ROW EXECUTE FUNCTION protect_closed_period();
