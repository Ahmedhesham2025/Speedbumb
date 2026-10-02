-- forget_me: the caller's device and everything tied to it is deleted; merged spots stay, anonymous.
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local');
insert into public.devices (id, share_enabled) values
  ('11111111-1111-1111-1111-111111111111', true),
  ('22222222-2222-2222-2222-222222222222', true);
insert into public.observations (device_id, client_obs_id, kind, geom, observed_hour)
select d::uuid, gen_random_uuid(), 'jolt', 'SRID=4326;POINT(28.4 23.4)', date_trunc('hour', now())
from unnest(array['11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222']) d;
do $$ begin perform public.aggregate_observations(); end $$;
insert into public.observations (device_id, client_obs_id, kind, geom, observed_hour) values
  ('11111111-1111-1111-1111-111111111111', gen_random_uuid(), 'jolt', 'SRID=4326;POINT(28.4 23.4)', date_trunc('hour', now()));
insert into public.fleets (id, name) overriding system value values (900001, 'F');
insert into public.drivers (id, fleet_id, display_name, device_id) overriding system value
values (900001, 900001, 'Driver', '11111111-1111-1111-1111-111111111111');
insert into public.trips (device_id, fleet_id, started_at) values ('11111111-1111-1111-1111-111111111111', 900001, now());
insert into public.crash_reports (device_id, stack) values ('11111111-1111-1111-1111-111111111111', 'trace');

set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select lives_ok($$ select public.forget_me() $$, 'a device can ask to be forgotten');
reset role;

select is((select count(*) from public.devices where id = '11111111-1111-1111-1111-111111111111'), 0::bigint, 'device row is gone');
select is((select count(*) from public.observations where device_id = '11111111-1111-1111-1111-111111111111'), 0::bigint, 'observations are gone');
select is((select count(*) from public.spot_contributors where device_id = '11111111-1111-1111-1111-111111111111'), 0::bigint, 'contributions are gone');
select is((select count(*) from public.trips where device_id = '11111111-1111-1111-1111-111111111111'), 0::bigint, 'trips are gone');
select is((select count(*) from public.crash_reports where device_id = '11111111-1111-1111-1111-111111111111'), 0::bigint, 'crash reports are gone');
select is((select device_id from public.drivers where id = 900001), null::uuid, 'the fleet driver is unlinked, not deleted');
select is((select count(*) from public.devices where id = '22222222-2222-2222-2222-222222222222'), 1::bigint, 'other devices are untouched');
select is(
  (select count(*) from public.spots s
   where extensions.st_dwithin(s.geom, 'SRID=4326;POINT(28.4 23.4)'::extensions.geography, 15)), 1::bigint,
  'the merged spot stays');

select * from finish();
rollback;
