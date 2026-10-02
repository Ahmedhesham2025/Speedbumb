-- register_device + submit_observations: validation, duplicates, consent, daily cap.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local'),
  ('33333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', 'c@test.local'),
  ('44444444-4444-4444-4444-444444444444', 'authenticated', 'authenticated', 'd@test.local');

-- C registered without sharing; D is near its daily cap.
insert into public.devices (id, share_enabled) values
  ('33333333-3333-3333-3333-333333333333', false),
  ('44444444-4444-4444-4444-444444444444', true);
insert into public.observations (device_id, client_obs_id, kind, geom, observed_hour)
select '44444444-4444-4444-4444-444444444444', gen_random_uuid(), 'jolt', 'SRID=4326;POINT(29 24)', date_trunc('hour', now())
from generate_series(1, 1999);

-- ---------------------------------------------------------------- device A
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';

select is(public.register_device('1.0', 34, 1, true), '11111111-1111-1111-1111-111111111111'::uuid,
  'register_device creates the caller''s device');
select is((select share_enabled from public.devices), true, 'sharing is stored');

select is(
  public.submit_observations(jsonb_build_array(
    jsonb_build_object('client_obs_id', 'a0000000-0000-0000-0000-000000000001', 'kind', 'jolt',
      'lat', 24.0, 'lon', 29.0, 'heading', 90, 'speed_kmh', 40, 'peak', 3.2, 'observed_at', now() - interval '20 minutes'),
    jsonb_build_object('client_obs_id', 'a0000000-0000-0000-0000-000000000002', 'kind', 'pass_clear',
      'lat', 24.001, 'lon', 29.001, 'observed_at', now()))),
  array['a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002']::uuid[],
  'a valid batch is accepted and acknowledged');

select is(
  public.submit_observations(jsonb_build_array(
    jsonb_build_object('client_obs_id', 'a0000000-0000-0000-0000-000000000001', 'kind', 'jolt',
      'lat', 24.0, 'lon', 29.0, 'observed_at', now()))),
  array['a0000000-0000-0000-0000-000000000001']::uuid[],
  'a duplicate is acknowledged');

select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 51.5, 'lon', -0.1, 'observed_at', now()))) $$,
  '22023', null, 'a point outside the Egypt/MENA box rejects the batch');
select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'teleport', 'lat', 24, 'lon', 29, 'observed_at', now()))) $$,
  '22023', null, 'an unknown kind rejects the batch');
select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 24, 'lon', 29, 'observed_at', now() - interval '60 days'))) $$,
  '22023', null, 'an observation older than 30 days rejects the batch');
select throws_ok(
  $$ select public.submit_observations((select jsonb_agg(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 24, 'lon', 29, 'observed_at', now())) from generate_series(1, 501))) $$,
  '22023', null, 'more than 500 rows is rejected');

select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', 'not-a-uuid', 'kind', 'jolt', 'lat', 24, 'lon', 29, 'observed_at', now()))) $$,
  '22023', null, 'a malformed uuid gives 22023');
select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 24, 'lon', 29, 'observed_at', 'last tuesday'))) $$,
  '22023', null, 'a malformed timestamp gives 22023');
select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 24, 'lon', 29, 'heading', 400, 'observed_at', now()))) $$,
  '22023', null, 'a heading outside 0..359 gives 22023');
select throws_ok(
  $$ select public.submit_observations(jsonb_build_array(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 24, 'lon', 29, 'speed_kmh', 99999, 'observed_at', now()))) $$,
  '22023', null, 'an impossible speed gives 22023');

-- ---------------------------------------------------------------- not registered / not sharing / capped
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select throws_ok(
  $$ select public.submit_observations('[]') $$, '42501', null, 'an unregistered device is rejected');

set local request.jwt.claims = '{"sub":"33333333-3333-3333-3333-333333333333","role":"authenticated"}';
select throws_ok(
  $$ select public.submit_observations('[]') $$, '42501', null, 'a device with sharing off is rejected');

set local request.jwt.claims = '{"sub":"44444444-4444-4444-4444-444444444444","role":"authenticated"}';
select throws_ok(
  $$ select public.submit_observations((select jsonb_agg(jsonb_build_object(
       'client_obs_id', gen_random_uuid(), 'kind', 'jolt', 'lat', 24, 'lon', 29, 'observed_at', now())) from generate_series(1, 2))) $$,
  '54000', null, 'the daily cap of 2000 rows per device holds');

-- ---------------------------------------------------------------- what was stored (as postgres)
reset role;
select is(
  (select count(*) from public.observations where device_id = '11111111-1111-1111-1111-111111111111'), 2::bigint,
  'only the two distinct observations were stored');
select is(
  (select count(*) from public.observations
   where device_id = '11111111-1111-1111-1111-111111111111' and observed_hour <> date_trunc('hour', observed_hour)), 0::bigint,
  'observation times are rounded to the hour');
select is(
  (select extensions.st_y(geom::extensions.geometry) from public.observations
   where client_obs_id = 'a0000000-0000-0000-0000-000000000001'), 24.0::double precision,
  'latitude is stored as sent');

select * from finish();
rollback;
