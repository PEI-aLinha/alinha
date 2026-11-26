-- A submitted NIS2 assessment of Componentes Águeda (#42): the consultant (Marta) can open it and evaluate it in the
-- demo without answering 136 questions first. Loaded only with the `demo` profile; same RNF04 exception as
-- R__demo_organization_profiles.sql (fixture data, no actor, so no audit history for these answers).
--
-- Pinned to nis2-qnrcs v1 (immutable, RNF18). Important entity, so the questionnaire goes up to the substantial
-- level (ScopeRules.maxLevel): every basic and substantial question has an answer. The answers are made up, with
-- governance and incident response weaker than the technical controls, as in a typical medium-sized factory.
-- DemoSeedIntegrationTest checks that they are exactly the questions of that questionnaire.
--
-- ON CONFLICT only on the seed's own ids: a re-run inserts nothing twice (the assessment by id, each answer by
-- answer_question_uq on assessment and question). Any other clash fails the start instead of skipping the seed:
-- e.g. an open nis2-qnrcs assessment of the factory (assessment_open_uq) would otherwise leave it without the
-- submitted one the demo expects.
-- Flyway runs this file again when it changes, but an edited answer does not replace the stored one: run
-- scripts/reset-demo.sh to load the new values.
--
-- max_level: until #179 (catalogue level scales, ADR 0007) is on main, the column only accepts enum names
-- ('SUBSTANTIAL'); after it, only level ids ('substantial'). Repeatable migrations run after every versioned one, so
-- the seed takes the spelling the current check constraint accepts. Once #179 is merged, write 'substantial'.
insert into assessment.assessment (
    id, tenant_id, framework, catalogue_version, max_level, nis2_classification, rules_version, scope_input, status,
    created_at, updated_at
) values (
    'de330000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'nis2-qnrcs', 1,
    (select case when pg_get_constraintdef(c.oid) like '%''SUBSTANTIAL''%' then 'SUBSTANTIAL' else 'substantial' end
     from pg_constraint c
     where c.conrelid = 'assessment.assessment'::regclass and c.conname = 'assessment_max_level_check'),
    'IMPORTANT', 'applicability-2026.11-v1',
    '{"size": {"employees": 120, "annualTurnoverEur": 18500000, "annualBalanceSheetEur": null},
      "publicBody": false,
      "nis2": {"entityTypes": ["MOTOR_VEHICLE_MANUFACTURING"], "criticalEntity": false, "significantImpact": false,
               "higherEducation": false},
      "gdpr": {"processesPersonalData": true, "regularOrRiskyProcessing": true, "specialOrCriminalData": false,
               "largeScaleMonitoring": false, "largeScaleSpecialData": false},
      "aiAct": {"usesAiSystems": false, "providesAiSystems": false, "highRiskArea": false,
                "interactsOrGeneratesContent": false}}'::jsonb,
    'SUBMITTED', now() - interval '10 days', now() - interval '2 days'
)
on conflict (id) do nothing;

-- The classification comes from the CNCS qualification act (R__demo_organization_scope.sql), registered before the
-- assessment started, so it is official. The column arrives with #179's assessment migrations: set it only when it
-- exists, and drop the guard once #179 is merged.
do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = 'assessment' and table_name = 'assessment'
                 and column_name = 'nis2_classification_official') then
        execute 'update assessment.assessment set nis2_classification_official = true '
             || 'where id = ''de330000-0000-4000-8000-000000000001''';
    end if;
end
$$;

insert into assessment.answer (id, tenant_id, assessment_id, question_id, value, answered_at)
select gen_random_uuid(), a.tenant_id, a.id, q.question_id, q.value, a.updated_at - interval '1 day'
from assessment.assessment a
cross join (values
    ('GR.CO-1-S-Q1', 'NO'),
    ('GR.CO-3-B-Q1', 'YES'),
    ('GR.CO-4-B-Q1', 'PARTIAL'),
    ('GR.CO-4-S-Q1', 'PARTIAL'),
    ('GR.CO-5-B-Q1', 'NO'),
    ('GR.CO-5-S-Q1', 'NO'),
    ('GR.CO-5-S-Q2', 'NO'),
    ('GR.CO-6-B-Q1', 'YES'),
    ('GR.CO-6-B-Q2', 'PARTIAL'),
    ('GR.GR-1-S-Q1', 'YES'),
    ('GR.GR-2-B-Q1', 'PARTIAL'),
    ('GR.GR-2-S-Q1', 'PARTIAL'),
    ('GR.GR-3-S-Q1', 'YES'),
    ('GR.GR-4-S-Q1', 'YES'),
    ('GR.GR-5-S-Q1', 'NO'),
    ('GR.FR-1-B-Q1', 'PARTIAL'),
    ('GR.FR-2-S-Q1', 'PARTIAL'),
    ('GR.FR-3-S-Q1', 'PARTIAL'),
    ('GR.FR-4-B-Q1', 'NO'),
    ('GR.FR-4-S-Q1', 'YES'),
    ('GR.PP-1-B-Q1', 'NO'),
    ('GR.PP-1-S-Q1', 'NO'),
    ('GR.PP-2-B-Q1', 'PARTIAL'),
    ('GR.PP-3-S-Q1', 'YES'),
    ('GR.CA-3-S-Q1', 'UNKNOWN'),
    ('GR.CA-3-S-Q2', 'PARTIAL'),
    ('GR.CA-5-S-Q1', 'YES'),
    ('GR.CA-7-B-Q1', 'YES'),
    ('GR.CA-7-S-Q1', 'NO'),
    ('GR.CA-8-S-Q1', 'NO'),
    ('ID.GA-1-B-Q1', 'NO'),
    ('ID.GA-2-B-Q1', 'NO'),
    ('ID.GA-3-S-Q1', 'PARTIAL'),
    ('ID.GA-4-B-Q1', 'UNKNOWN'),
    ('ID.GA-5-B-Q1', 'PARTIAL'),
    ('ID.GA-7-B-Q1', 'PARTIAL'),
    ('ID.GA-7-B-Q2', 'NO'),
    ('ID.GA-7-S-Q1', 'PARTIAL'),
    ('ID.GA-7-S-Q2', 'NO'),
    ('ID.GA-8-S-Q1', 'PARTIAL'),
    ('ID.AR-1-B-Q1', 'NO'),
    ('ID.AR-1-S-Q1', 'YES'),
    ('ID.AR-4-B-Q1', 'YES'),
    ('ID.AR-5-B-Q1', 'YES'),
    ('ID.AR-5-S-Q1', 'YES'),
    ('ID.AR-6-B-Q1', 'YES'),
    ('ID.AR-6-S-Q1', 'YES'),
    ('ID.AR-7-B-Q1', 'YES'),
    ('ID.AR-7-S-Q1', 'PARTIAL'),
    ('ID.AR-7-S-Q2', 'PARTIAL'),
    ('ID.MC-3-B-Q1', 'PARTIAL'),
    ('ID.MC-3-S-Q1', 'YES'),
    ('PR.GA-1-B-Q1', 'YES'),
    ('PR.GA-1-B-Q2', 'NO'),
    ('PR.GA-1-S-Q1', 'PARTIAL'),
    ('PR.GA-1-S-Q2', 'PARTIAL'),
    ('PR.GA-2-S-Q1', 'YES'),
    ('PR.GA-3-B-Q1', 'PARTIAL'),
    ('PR.GA-3-S-Q1', 'YES'),
    ('PR.GA-3-S-Q2', 'YES'),
    ('PR.GA-5-B-Q1', 'NO'),
    ('PR.GA-5-B-Q2', 'PARTIAL'),
    ('PR.GA-5-B-Q3', 'PARTIAL'),
    ('PR.GA-5-S-Q1', 'PARTIAL'),
    ('PR.GA-5-S-Q2', 'NO'),
    ('PR.GA-5-S-Q3', 'PARTIAL'),
    ('PR.GA-6-B-Q1', 'YES'),
    ('PR.GA-6-S-Q1', 'YES'),
    ('PR.GA-6-S-Q2', 'YES'),
    ('PR.GA-7-B-Q1', 'YES'),
    ('PR.GA-7-B-Q2', 'YES'),
    ('PR.GA-7-S-Q1', 'NO'),
    ('PR.GA-7-S-Q2', 'NO'),
    ('PR.GA-7-S-Q3', 'YES'),
    ('PR.FC-1-B-Q1', 'PARTIAL'),
    ('PR.FC-1-S-Q1', 'YES'),
    ('PR.FC-1-S-Q2', 'NO'),
    ('PR.FC-2-B-Q1', 'YES'),
    ('PR.FC-2-S-Q1', 'YES'),
    ('PR.SD-1-S-Q1', 'YES'),
    ('PR.SD-1-S-Q2', 'PARTIAL'),
    ('PR.SD-2-B-Q1', 'YES'),
    ('PR.SD-5-B-Q1', 'PARTIAL'),
    ('PR.SD-5-B-Q2', 'NO'),
    ('PR.SD-5-S-Q1', 'YES'),
    ('PR.SD-5-S-Q2', 'YES'),
    ('PR.SD-5-S-Q3', 'NO'),
    ('PR.SD-5-S-Q4', 'PARTIAL'),
    ('PR.SP-1-B-Q1', 'YES'),
    ('PR.SP-1-S-Q1', 'YES'),
    ('PR.SP-2-B-Q1', 'YES'),
    ('PR.SP-2-B-Q2', 'PARTIAL'),
    ('PR.SP-2-S-Q1', 'PARTIAL'),
    ('PR.SP-3-B-Q1', 'YES'),
    ('PR.SP-3-B-Q2', 'YES'),
    ('PR.SP-4-S-Q1', 'YES'),
    ('PR.SP-4-S-Q2', 'NO'),
    ('PR.RI-1-B-Q1', 'PARTIAL'),
    ('PR.RI-1-B-Q2', 'NO'),
    ('PR.RI-1-B-Q3', 'NO'),
    ('PR.RI-1-S-Q1', 'YES'),
    ('PR.RI-1-S-Q2', 'PARTIAL'),
    ('PR.RI-2-S-Q1', 'PARTIAL'),
    ('PR.RI-4-S-Q1', 'PARTIAL'),
    ('PR.RI-4-S-Q2', 'YES'),
    ('PR.RI-5-S-Q1', 'PARTIAL'),
    ('DE.MC-1-B-Q1', 'YES'),
    ('DE.MC-1-S-Q1', 'PARTIAL'),
    ('DE.MC-2-S-Q1', 'PARTIAL'),
    ('DE.MC-4-B-Q1', 'UNKNOWN'),
    ('DE.MC-5-B-Q1', 'NO'),
    ('DE.MC-5-B-Q2', 'PARTIAL'),
    ('DE.MC-5-S-Q1', 'NO'),
    ('DE.MC-6-S-Q1', 'YES'),
    ('DE.MC-6-S-Q2', 'UNKNOWN'),
    ('DE.MC-7-S-Q1', 'NO'),
    ('DE.AE-1-S-Q1', 'PARTIAL'),
    ('DE.AE-2-B-Q1', 'NO'),
    ('DE.AE-4-S-Q1', 'NO'),
    ('DE.AE-6-B-Q1', 'YES'),
    ('RS.GI-1-B-Q1', 'NO'),
    ('RS.GI-1-S-Q1', 'NO'),
    ('RS.GI-2-S-Q1', 'NO'),
    ('RS.GI-3-S-Q1', 'NO'),
    ('RS.GI-5-S-Q1', 'YES'),
    ('RS.NC-1-B-Q1', 'PARTIAL'),
    ('RS.NC-1-B-Q2', 'YES'),
    ('RS.NC-1-S-Q1', 'UNKNOWN'),
    ('RS.MI-1-S-Q1', 'NO'),
    ('RS.MI-1-S-Q2', 'NO'),
    ('RC.PR-1-B-Q1', 'PARTIAL'),
    ('RC.PR-3-B-Q1', 'YES'),
    ('RC.PR-5-S-Q1', 'NO'),
    ('RC.PR-6-S-Q1', 'UNKNOWN'),
    ('RC.CO-1-S-Q1', 'YES'),
    ('RC.CO-2-S-Q1', 'PARTIAL')
) as q (question_id, value)
where a.id = 'de330000-0000-4000-8000-000000000001'
on conflict (assessment_id, question_id) do nothing;
