-- Permissions for society-service master data and resident self-service.
--   society:view      read towers, flats, locations, facilities, parking (everyone in the society)
--   household:manage  a resident manages their own flat's vehicles and domestic staff

INSERT INTO permission (code, module, action, description) VALUES
  ('society:view',     'society',   'view',   'See towers, flats, locations, facilities and parking'),
  ('household:manage', 'household', 'manage', 'Manage own flat''s vehicles and domestic staff');

UPDATE role_template SET permissions = permissions || '{society:view}'::text[]
 WHERE NOT ('society:view' = ANY (permissions));

UPDATE role_template SET permissions = permissions || '{household:manage}'::text[]
 WHERE code IN ('RESIDENT_OWNER', 'RESIDENT_TENANT') AND NOT ('household:manage' = ANY (permissions));

-- Roles already copied into societies: RLS is forced for the owner too, so lift it for this update.
ALTER TABLE role NO FORCE ROW LEVEL SECURITY;

UPDATE role SET permissions = permissions || '{society:view}'::text[]
 WHERE is_system AND NOT ('society:view' = ANY (permissions));

UPDATE role SET permissions = permissions || '{household:manage}'::text[]
 WHERE is_system AND code IN ('RESIDENT_OWNER', 'RESIDENT_TENANT') AND NOT ('household:manage' = ANY (permissions));

ALTER TABLE role FORCE ROW LEVEL SECURITY;
