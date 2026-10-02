-- Row-level security role matrix: anon, device owner, other device, fleet admin, fleet viewer, other fleet.
begin;
create extension if not exists pgtap with schema extensions;
select plan(36);

-- ---------------------------------------------------------------- fixtures (as postgres)
insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'authenticated', 'authenticated', 'admin-a@test.local'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'authenticated', 'authenticated', 'admin-b@test.local'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'authenticated', 'authenticated', 'viewer-a@test.local');

insert into public.devices (id, share_enabled) values
  ('11111111-1111-1111-1111-111111111111', true),
  ('22222222-2222-2222-2222-222222222222', true);

insert into public.observations (device_id, client_obs_id, kind, geom, observed_hour) values
  ('11111111-1111-1111-1111-111111111111', gen_random_uuid(), 'jolt', 'SRID=4326;POINT(29.0 24.0)', date_trunc('hour', now()));

insert into public.spots (id, geom, status) overriding system value values
  (900001, 'SRID=4326;POINT(29.0 24.0)', 'confirmed'),
  (900002, 'SRID=4326;POINT(29.0001 24.0)', 'candidate'),
  (900003, 'SRID=4326;POINT(29.15 24.0)', 'confirmed');   -- about 15 km east

insert into public.fleets (id, name) overriding system value values (900001, 'Test A'), (900002, 'Test B');
insert into public.memberships (user_id, fleet_id, role) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 900001, 'admin'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 900001, 'viewer'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 900002, 'admin');
insert into public.drivers (id, fleet_id, display_name) overriding system value values
  (900001, 900001, 'Driver A1'), (900002, 900002, 'Driver B1');
insert into public.invites (code, fleet_id, driver_id) values
  ('TEST-A-0000000000', 900001, 900001), ('TEST-B-0000000000', 900002, 900002);
insert into public.trips (id, device_id, fleet_id, driver_id, started_at) overriding system value values
  (900001, '11111111-1111-1111-1111-111111111111', 900001, 900001, now()),
  (900002, '22222222-2222-2222-2222-222222222222', 900002, 900002, now()),
  (900003, '22222222-2222-2222-2222-222222222222', null, null, now());
insert into public.trip_events (trip_id, kind, at) values (900001, 'harsh_brake', now()), (900002, 'harsh_brake', now());

select is(
  (select count(*) from pg_tables where schemaname = 'public' and not rowsecurity), 0::bigint,
  'RLS is enabled on every public table');

-- ---------------------------------------------------------------- anon
set local role anon;
set local request.jwt.claims = '{"role":"anon"}';

select is(
  (select count(*) from information_schema.role_table_grants where grantee = 'anon' and table_schema = 'public'),
  0::bigint, 'anon has no table privileges at all');
select throws_ok($$ select * from public.spots $$, '42501', null, 'anon cannot read spots');
select throws_ok($$ select * from public.devices $$, '42501', null, 'anon cannot read devices');
select throws_ok($$ select * from public.trips $$, '42501', null, 'anon cannot read trips');
select throws_ok($$ select public.spots_near(24.0, 29.0) $$, '42501', null, 'anon cannot call spots_near');
select throws_ok($$ select public.submit_observations('[]') $$, '42501', null, 'anon cannot call submit_observations');

-- ---------------------------------------------------------------- device A
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';

select throws_ok($$ select * from public.observations $$, '42501', null, 'device cannot read observations, not even its own');
select throws_ok($$ select * from public.spot_contributors $$, '42501', null, 'device cannot read spot_contributors');
select throws_ok($$ select * from public.app_settings $$, '42501', null, 'device cannot read app_settings');
select is((select count(*) from public.spots where id = 900001), 1::bigint, 'device sees a confirmed spot');
select is((select count(*) from public.spots where id = 900002), 0::bigint, 'device does not see a candidate spot');
select throws_ok($$ insert into public.spots (geom) values ('SRID=4326;POINT(29 24)') $$, '42501', null, 'device cannot write spots');
select is((select count(*) from public.devices where id = '11111111-1111-1111-1111-111111111111'), 1::bigint, 'device sees its own row');
select is((select count(*) from public.devices where id = '22222222-2222-2222-2222-222222222222'), 0::bigint, 'device cannot see another device');
select throws_ok($$ update public.devices set trust = 99 $$, '42501', null, 'device cannot raise its own trust');
select lives_ok($$ update public.devices set share_enabled = false $$, 'device can turn sharing off');
select throws_ok($$ update public.devices set id = '22222222-2222-2222-2222-222222222222' $$, '42501', null, 'device cannot change its id');
select is(
  (select array_agg(id order by id) from public.spots_near(24.0, 29.0, 1000000)), array[900001::bigint],
  'spots_near returns confirmed spots only and caps the radius at 10 km');
select is((select array_agg(id order by id) from public.trips where id >= 900001), array[900001::bigint], 'device sees only its own trips');
select is((select array_agg(trip_id) from public.trip_events where trip_id >= 900001), array[900001::bigint], 'device sees the events of its own trip only');
select lives_ok($$ select public.submit_crash_report('1.0', 'Pixel', 'trace') $$, 'device can file a crash report through the RPC');
select throws_ok(
  $$ insert into public.crash_reports (device_id, stack) values ('22222222-2222-2222-2222-222222222222', 'trace') $$,
  '42501', null, 'device cannot insert crash reports directly');
select throws_ok($$ select * from public.crash_reports $$, '42501', null, 'device cannot read crash reports');
select throws_ok($$ select public.aggregate_observations() $$, '42501', null, 'clients cannot run the aggregation');
select is((select count(*) from public.fleets where id >= 900001), 0::bigint, 'a non-member sees no fleets');

-- ---------------------------------------------------------------- fleet A admin
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';

select is((select array_agg(id) from public.fleets where id >= 900001), array[900001::bigint], 'admin A sees only fleet A');
select is((select array_agg(id) from public.drivers where id >= 900001), array[900001::bigint], 'admin A sees only fleet A drivers');
select is((select array_agg(id order by id) from public.trips where id >= 900001), array[900001::bigint], 'admin A sees only fleet A trips');
select is((select count(*) from public.trip_events where trip_id = 900002), 0::bigint, 'admin A cannot see fleet B trip events');
select is((select array_agg(code) from public.invites where code like 'TEST-%'), array['TEST-A-0000000000'], 'admin A sees only fleet A invites');
select lives_ok($$ insert into public.vehicles (fleet_id, label) values (900001, 'New van') $$, 'admin A can add a vehicle to fleet A');
select throws_ok($$ insert into public.vehicles (fleet_id, label) values (900002, 'Sneaky') $$, '42501', null, 'admin A cannot add a vehicle to fleet B');
select throws_ok(
  $$ update public.drivers set device_id = '11111111-1111-1111-1111-111111111111' where id = 900001 $$,
  '42501', null, 'admin cannot link a driver to a device directly');

-- ---------------------------------------------------------------- fleet A viewer
set local request.jwt.claims = '{"sub":"cccccccc-cccc-cccc-cccc-cccccccccccc","role":"authenticated"}';

select is((select count(*) from public.invites), 0::bigint, 'viewer cannot see invite codes');
select throws_ok($$ insert into public.vehicles (fleet_id, label) values (900001, 'Nope') $$, '42501', null, 'viewer cannot add vehicles');

reset role;
select * from finish();
rollback;
