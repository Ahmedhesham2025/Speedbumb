-- aggregate_observations: matching, confirmation thresholds, clears, cleanup.
begin;
create extension if not exists pgtap with schema extensions;
select plan(14);

insert into auth.users (id, aud, role, email)
select ('d0000000-0000-0000-0000-00000000000' || i)::uuid, 'authenticated', 'authenticated', 'd' || i || '@test.local'
from generate_series(1, 5) i;
insert into public.devices (id, share_enabled)
select ('d0000000-0000-0000-0000-00000000000' || i)::uuid, true from generate_series(1, 5) i;

-- The migration ships confirm_devices = 2 (pilot); start from that explicitly.
update public.app_settings set value = '2' where key = 'confirm_devices';

-- Places (all in empty desert, ~0.00003 deg = ~3 m apart):
--   X 23.50,28.50  three devices, heading ~90         -> confirmed
--   X  same spot, heading 270 from one device          -> separate candidate (other direction)
--   Y 23.60,28.60  one device                          -> candidate
--   Z 23.70,28.70  two devices, confirm_devices = 2      -> confirmed
--   W 23.80,28.80  three hits then four clears         -> stays candidate (hit ratio < 0.5)
--   V 23.90,28.90  a clear with no spot nearby         -> no spot
insert into public.observations (device_id, client_obs_id, kind, geom, heading, peak, observed_hour)
select ('d0000000-0000-0000-0000-00000000000' || v.d)::uuid, gen_random_uuid(), v.k,
       extensions.st_setsrid(extensions.st_makepoint(v.lon, v.lat), 4326)::extensions.geography,
       v.h, 2.0, date_trunc('hour', now())
from (values
  (1, 1, 'jolt',       23.50000, 28.50000,  90),
  (2, 2, 'jolt',       23.50003, 28.50002,  85),
  (3, 3, 'known_hit',  23.49998, 28.50003, 100),
  (4, 2, 'jolt',       23.50001, 28.50000, 270),
  (5, 1, 'jolt',       23.60000, 28.60000,  45),
  (6, 1, 'jolt',       23.70000, 28.70000,   0),
  (7, 5, 'jolt',       23.70002, 28.70001,  10),
  (8, 1, 'jolt',       23.80000, 28.80000, 180),
  (9, 2, 'jolt',       23.80001, 28.80000, 180),
  (10, 3, 'jolt',      23.80000, 28.80001, 180),
  (11, 4, 'pass_clear', 23.80000, 28.80000, 180),
  (12, 4, 'pass_clear', 23.80001, 28.80000, 180),
  (13, 5, 'pass_clear', 23.80000, 28.80001, 180),
  (14, 5, 'pass_clear', 23.80000, 28.80000, 180),
  (15, 1, 'pass_clear', 23.90000, 28.90000,   0)
) v(n, d, k, lat, lon, h)
order by v.n;

-- An old, already merged observation that must be cleaned up.
insert into public.observations (device_id, client_obs_id, kind, geom, observed_hour, processed, created_at)
values ('d0000000-0000-0000-0000-000000000001', gen_random_uuid(), 'jolt', 'SRID=4326;POINT(28.5 23.5)',
        now() - interval '2 days', true, now() - interval '2 days');

select is(public.aggregate_observations(), 15, 'every new observation is processed');
select is((select count(*) from public.observations where not processed), 0::bigint, 'nothing left unprocessed');
select is((select count(*) from public.observations where created_at < now() - interval '1 day'), 0::bigint,
  'processed observations older than a day are deleted');
select is((select count(*) from public.observations), 15::bigint, 'observations from the last day stay for the upload cap');

create temp table near_spot on commit drop as
select p.name, s.status, s.n_devices, s.n_hits, s.n_clear, s.heading
from (values ('X', 23.5, 28.5), ('Y', 23.6, 28.6), ('Z', 23.7, 28.7), ('W', 23.8, 28.8), ('V', 23.9, 28.9)) p(name, lat, lon)
join public.spots s
  on extensions.st_dwithin(s.geom, extensions.st_setsrid(extensions.st_makepoint(p.lon, p.lat), 4326)::extensions.geography, 15);

select is((select status from near_spot where name = 'X' and heading = 90), 'confirmed', 'three devices confirm a spot');
select is((select n_devices from near_spot where name = 'X' and heading = 90), 3, 'the spot counts three devices');
select is((select status from near_spot where name = 'X' and heading = 270), 'candidate',
  'the opposite direction is a separate candidate');
select is((select status from near_spot where name = 'Y'), 'candidate', 'one device leaves a candidate');
select is((select status from near_spot where name = 'Z'), 'confirmed', 'two devices confirm when confirm_devices = 2');
select results_eq($$ select status, n_hits, n_clear from near_spot where name = 'W' $$,
  $$ values ('candidate'::text, 3, 4) $$, 'more clears than hits keeps a spot unconfirmed');
select is((select count(*) from near_spot where name = 'V'), 0::bigint, 'a clear with no spot creates nothing');

select is(public.aggregate_observations(), 0, 'a second run has nothing to do');

-- Raise the setting to 3 (public launch): two devices are no longer enough.
update public.app_settings set value = '3' where key = 'confirm_devices';
insert into public.observations (device_id, client_obs_id, kind, geom, heading, observed_hour)
select ('d0000000-0000-0000-0000-00000000000' || d)::uuid, gen_random_uuid(), 'jolt', 'SRID=4326;POINT(28.4 23.4)', 0, date_trunc('hour', now())
from generate_series(1, 2) d;
do $$ begin perform public.aggregate_observations(); end $$;
select is(
  (select status from public.spots where extensions.st_dwithin(geom, 'SRID=4326;POINT(28.4 23.4)'::extensions.geography, 15)),
  'candidate', 'with confirm_devices = 3 two devices stay a candidate');

-- A missing setting falls back to 3.
delete from public.app_settings where key = 'confirm_devices';
insert into public.observations (device_id, client_obs_id, kind, geom, heading, observed_hour)
select ('d0000000-0000-0000-0000-00000000000' || d)::uuid, gen_random_uuid(), 'jolt', 'SRID=4326;POINT(28.3 23.3)', 0, date_trunc('hour', now())
from generate_series(1, 2) d;
do $$ begin perform public.aggregate_observations(); end $$;
select is(
  (select status from public.spots where extensions.st_dwithin(geom, 'SRID=4326;POINT(28.3 23.3)'::extensions.geography, 15)),
  'candidate', 'without the setting, two devices stay a candidate (default 3)');

select * from finish();
rollback;
