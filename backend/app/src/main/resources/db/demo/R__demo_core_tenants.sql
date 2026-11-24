-- Demo tenants (#42) in core.tenant, as if each organization had been registered through the wizard (ADR 0004).
-- Loaded only with the `demo` profile; same RNF04 exception as R__demo_organization_profiles.sql (fixture data, no
-- actor). The tenant ids are the ones in deploy/keycloak/alinha-realm.json.
--
-- owner_subject is normally the Keycloak `sub` of whoever registered the organization. The realm import gives the seed
-- users random ids, so the demo rows use a `demo-seed:` subject that no Keycloak account can have: nobody "owns" a
-- demo tenant, and a real self-registration is never refused because of one.
insert into core.tenant (id, owner_subject, created_at)
select id::uuid, 'demo-seed:' || id, now()
from (values
    ('11111111-1111-4111-8111-111111111111'),
    ('22222222-2222-4222-8222-222222222222'),
    ('33333333-3333-4333-8333-333333333333'),
    ('44444444-4444-4444-8444-444444444444'),
    ('55555555-5555-4555-8555-555555555555'),
    ('66666666-6666-4666-8666-666666666666'),
    ('77777777-7777-4777-8777-777777777777'),
    ('88888888-8888-4888-8888-888888888888'),
    ('99999999-9999-4999-8999-999999999999'),
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
    ('cccccccc-cccc-4ccc-8ccc-cccccccccccc')
) as demo (id)
on conflict do nothing;
