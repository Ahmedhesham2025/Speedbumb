-- OSM speed bumps (ODbL prior): RLS and grants, the idempotent import upsert and prune, validation, osm_bumps_near
-- (signed-in only, nearest first, radius capped at 10 km, at most 2000).
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, aud, role, email)
values ('0b000000-0000-0000-0000-000000000001', 'authenticated', 'authenticated', 'osm@test.local');

-- ---------------------------------------------------------------- shape, RLS, grants
select ok((select relrowsecurity from pg_class where oid = 'public.osm_bumps'::regclass), 'RLS is on for osm_bumps');
select is((select count(*) from pg_policies where schemaname = 'public' and tablename = 'osm_bumps'), 0::bigint,
          'osm_bumps has no policies: no direct client access');
select is(array[has_table_privilege('anon', 'public.osm_bumps', 'select'),
                has_table_privilege('authenticated', 'public.osm_bumps', 'select'),
                has_table_privilege('authenticated', 'public.osm_bumps', 'insert')],
          array[false, false, false], 'clients have no table privileges on osm_bumps');
select is(array(select has_function_privilege(r, f, 'execute')
                from unnest(array['anon', 'authenticated']) r,
                     unnest(array['public.osm_bumps_import(jsonb, timestamptz)', 'public.osm_bumps_prune(timestamptz)',
                                  'public.osm_bumps_status()']) f),
          array[false, false, false, false, false, false], 'clients cannot import, prune or read the status');
select is(array[has_function_privilege('anon', 'public.osm_bumps_near(double precision, double precision, integer)', 'execute'),
                has_function_privilege('authenticated', 'public.osm_bumps_near(double precision, double precision, integer)', 'execute'),
                has_function_privilege('service_role', 'public.osm_bumps_import(jsonb, timestamptz)', 'execute')],
          array[false, true, true], 'only signed-in users read nearby bumps; the service role imports');

-- ---------------------------------------------------------------- import (service role, the owner's tool)
-- Test only (rolled back): the first run's batch.
create function public.t_osm_batch() returns jsonb language sql as $$
  select jsonb_build_array(
    jsonb_build_object('osm_id', 'n1', 'lat', 30.0, 'lon', 31.0, 'kind', 'hump'),
    jsonb_build_object('osm_id', 'n2', 'lat', 30.001, 'lon', 31.0, 'kind', 'bump'),      -- ~110 m north
    jsonb_build_object('osm_id', 'w3', 'lat', 30.05, 'lon', 31.0, 'kind', 'table'),      -- ~5.6 km north
    jsonb_build_object('osm_id', 'n5', 'lat', 30.11, 'lon', 31.0, 'kind', 'yes'),        -- ~12.2 km north
    jsonb_build_object('osm_id', 'n4', 'lat', 30.2, 'lon', 31.0, 'kind', 'speed_bump')); -- ~22 km north
$$;
grant execute on function public.t_osm_batch() to service_role;
set local role service_role;
select is(public.osm_bumps_import(public.t_osm_batch(), '2026-10-17 10:00+00'), 5, 'a first import stores 5 bumps');
select is(public.osm_bumps_import(public.t_osm_batch(), '2026-10-17 10:00+00'), 5, 'the same batch again is accepted');
reset role;
select is((select count(*) from public.osm_bumps), 5::bigint, 'importing it twice leaves 5 rows: the upsert is idempotent');
select results_eq($$ select kind, round(lat::numeric, 3) from public.osm_bumps where osm_id = 'n2' $$,
                  $$ values ('bump'::text, 30.001) $$, 'kind and position are stored as sent');
set local role service_role;
select throws_ok($$ select public.osm_bumps_import(jsonb_build_array(
                      jsonb_build_object('osm_id', 'n9', 'lat', 51.5, 'lon', -0.1, 'kind', 'hump')), now()) $$,
                 '22023', null, 'a bump outside Egypt''s box rejects the batch');
select throws_ok($$ select public.osm_bumps_import(jsonb_build_array(
                      jsonb_build_object('osm_id', 'r9', 'lat', 30, 'lon', 31, 'kind', 'chicane')), now()) $$,
                 '22023', null, 'an unknown element type or kind rejects the batch');
-- A later run that no longer sees n4 (removed in OSM) and moves n1: prune drops what the run didn't see.
select is(public.osm_bumps_import(jsonb_build_array(
            jsonb_build_object('osm_id', 'n1', 'lat', 30.0002, 'lon', 31.0, 'kind', 'cushion'),
            jsonb_build_object('osm_id', 'n2', 'lat', 30.001, 'lon', 31.0, 'kind', 'bump'),
            jsonb_build_object('osm_id', 'w3', 'lat', 30.05, 'lon', 31.0, 'kind', 'table'),
            jsonb_build_object('osm_id', 'n5', 'lat', 30.11, 'lon', 31.0, 'kind', 'yes')), '2026-10-24 10:00+00'),
          4, 'a weekly run updates what it sees');
select is(public.osm_bumps_prune('2026-10-24 10:00+00'), 1, 'prune deletes the bump the run no longer saw');
select is(public.osm_bumps_status() ->> 'count', '4', 'the status counts the bumps left');
reset role;

-- ---------------------------------------------------------------- reading (signed in)
set local role authenticated;
set local request.jwt.claims = '{"sub":"0b000000-0000-0000-0000-000000000001","role":"authenticated"}';
select results_eq($$ select osm_id, kind from public.osm_bumps_near(30.0, 31.0, 1000) $$,
                  $$ values ('n1'::text, 'cushion'::text), ('n2', 'bump') $$,
                  'osm_bumps_near returns the bumps within the radius, nearest first, with the moved position and kind');
select is((select array_agg(osm_id order by osm_id) from public.osm_bumps_near(30.0, 31.0, 1000000)),
          array['n1', 'n2', 'w3'], 'the radius is capped at 10 km (n5 at ~12 km is left out)');
reset role;
set local role anon;
select throws_ok($$ select * from public.osm_bumps_near(30.0, 31.0) $$, '42501', null, 'anon cannot read OSM bumps');
reset role;

select * from finish();
rollback;
