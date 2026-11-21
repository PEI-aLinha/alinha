-- Demo organization profiles for the tenants in deploy/keycloak/alinha-realm.json (see deploy/keycloak/README.md).
-- Without them /api/me falls back to the tenant id as the organization name. Loaded only with the `demo` profile.
-- ON CONFLICT: a profile registered through the wizard (same tenant or NIF) is never overwritten.
insert into organization.organization_profile (
    id, tenant_id, version, name, nif, cae, sector, subsector, services, employees, turnover,
    street, postal_code, city, phone, email, permanent_contact_name, permanent_contact_phone, registered_at, updated_at
) values
    ('de300000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 0,
     'Componentes Águeda, Lda.', '512345678', '29320', 'industria', 'automoveis',
     'Fabrico de componentes metálicos para a indústria automóvel.', 120, 18500000,
     'Zona Industrial do Casarão, Lote 12', '3750-325', 'Águeda', '234600100', 'geral@compagueda.pt',
     'Rui Santos', '912345678', now(), now()),
    ('de300000-0000-4000-8000-000000000002', '22222222-2222-4222-8222-222222222222', 0,
     'Metalúrgica de Aveiro, S.A.', '509876544', '25110', 'outro', null,
     'Fabrico de estruturas metálicas para construção.', 260, 42000000,
     'Rua da Indústria, 45', '3800-120', 'Aveiro', '234400200', 'geral@metalaveiro.pt',
     'Ana Costa', '913456789', now(), now())
on conflict do nothing;
