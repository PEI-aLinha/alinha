-- Applicability answers and CNCS qualification of Componentes Águeda (the factory in the personas, #42), so the
-- scope page and the NIS2 assessment start from the answers Rui gave. Loaded only with the `demo` profile; same RNF04
-- exception as R__demo_organization_profiles.sql. ON CONFLICT: answers saved through the app are never overwritten.
--
-- Medium company (120 employees, 18.5 M€) making motor-vehicle components (RJC annex II, 5.e)): important entity.
-- Personal data of employees and clients, regular processing; no AI. Must match the scope_input of the assessment in
-- R__demo_assessment.sql.
insert into organization.applicability_answers (
    id, tenant_id, version, public_body, annual_balance_sheet, nis2_entity_types, critical_entity, significant_impact,
    higher_education, processes_personal_data, regular_or_risky_processing, special_or_criminal_data,
    large_scale_monitoring, large_scale_special_data, uses_ai_systems, provides_ai_systems, high_risk_area,
    interacts_or_generates_content, answered_at, updated_at
) values (
    'de310000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 0, false, null,
    array['MOTOR_VEHICLE_MANUFACTURING'], false, false,
    false, true, true, false,
    false, false, false, false, false,
    false, now() - interval '14 days', now() - interval '14 days'
)
on conflict do nothing;

-- Qualification act received from the CNCS (reference made up for the demo).
insert into organization.cncs_qualification (
    id, tenant_id, version, classification, act_date, act_reference, registered_at, updated_at
) values (
    'de320000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 0, 'IMPORTANT',
    date '2026-09-15', 'CNCS/QUAL/2026/0412', now() - interval '13 days', now() - interval '13 days'
)
on conflict do nothing;
