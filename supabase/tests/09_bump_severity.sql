-- v2 bumps (B1): band edges, the backfill, old (1.7) and v2 payloads, no new potholes, legacy cleared by a v2 hit,
-- axle + strong = full, total hits, spots_near compatibility, spots_near_v2, training bands, moving the band edges.
begin;
create extension if not exists pgtap with schema extensions;
select plan(28);

insert into auth.users (id, aud, role, email)
select ('e0000000-0000-0000-0000-00000000000' || i)::uuid, 'authenticated', 'authenticated', 'e' || i || '@test.local'
from generate_series(1, 2) i;
insert into public.devices (id, share_enabled)
select ('e0000000-0000-0000-0000-00000000000' || i)::uuid, true from generate_series(1, 2) i;
update public.app_settings set value = '1' where key = 'confirm_devices';  -- the hosted project's value
-- A training sample (test only, rolled back): 4 s of int16 values at 50 Hz with the given classification.
create function public.t_band_sample(cid text, cls text) returns jsonb language sql as $$
  select jsonb_build_object('client_sample_id', cid, 'day', current_date, 'decision', 'hit', 'classification', cls,
    'placement', 'mounted', 'speed_kmh', 30, 'rate_hz', 50, 'pre_ms', 1000,
    'accel_v', encode(decode(repeat('0100', 200), 'hex'), 'base64'));
$$;
grant execute on function public.t_band_sample(text, text) to authenticated;

-- ---------------------------------------------------------------- bands
select is(array(select value from public.app_settings where key in ('severity_moderate_min', 'severity_strong_min')
                order by key),
          array['3.5', '5.0'], 'the band edges ship as 3.5 and 5.0 m/s²');
select is(array[public.severity_band(3.49), public.severity_band(3.5), public.severity_band(4.99),
                public.severity_band(5.0), public.severity_band(null)],
          array['mild', 'moderate', 'moderate', 'strong', null], 'mild < 3.5 <= moderate < 5.0 <= strong; no severity, no band');

-- ---------------------------------------------------------------- backfill (the migration ran it; here on spots as they were)
insert into public.spots (id, geom, heading, kind, side, severity, n_devices, n_hits, status) overriding system value values
  (900001, 'SRID=4326;POINT(28.10 23.10)', 90, 'pothole', 'right', 6.0, 2, 5, 'confirmed'),
  (900002, 'SRID=4326;POINT(28.11 23.10)', 90, 'bump', 'both', 4.0, 1, 2, 'confirmed'),
  (900003, 'SRID=4326;POINT(28.12 23.10)', 90, 'bump', 'left', 2.0, 1, 1, 'confirmed'),
  (900004, 'SRID=4326;POINT(28.13 23.10)', 90, null, null, null, 1, 1, 'candidate'),
  (900005, 'SRID=4326;POINT(28.14 23.10)', 90, 'pothole', 'left', 3.0, 1, 1, 'confirmed');
insert into public.spot_contributors (spot_id, device_id, hits)
select v.s, ('e0000000-0000-0000-0000-00000000000' || v.d)::uuid, v.h
from (values (900001, 1, 3), (900001, 2, 2), (900002, 1, 2), (900003, 1, 1), (900004, 1, 1), (900005, 1, 1)) v(s, d, h);
select ok(public.backfill_bump_severity() >= 5, 'the backfill can run again');
select results_eq(
  $$ select id, legacy_pothole, confidence, severity_band from public.spots where id between 900001 and 900005 order by id $$,
  $$ values (900001::bigint, true, 'soft'::text, 'strong'::text), (900002, false, 'full', 'moderate'),
            (900003, false, 'soft', 'mild'), (900004, false, 'soft', null), (900005, true, 'soft', 'mild') $$,
  'backfill: a pothole becomes a legacy, soft bump; two hits (even from one phone) make a bump full; bands from severity');

-- ---------------------------------------------------------------- uploads: a 1.7 payload as before, a v2 payload
set local role authenticated;
set local request.jwt.claims = '{"sub":"e0000000-0000-0000-0000-000000000001","role":"authenticated"}';
-- What 1.7 sends. kind_score 0.9 and side_score 0.8 used to make "a pothole on the right".
select is(public.submit_observations(jsonb_build_array(
    jsonb_build_object('client_obs_id', 'e1000000-0000-0000-0000-000000000001', 'kind', 'jolt', 'lat', 23.2, 'lon', 28.2,
      'heading', 90, 'speed_kmh', 40, 'peak', 7.5, 'kind_score', 0.9, 'side_score', 0.8, 'observed_at', now()),
    jsonb_build_object('client_obs_id', 'e1000000-0000-0000-0000-000000000002', 'kind', 'known_hit', 'lat', 23.1,
      'lon', 28.1, 'heading', 90, 'speed_kmh', 30, 'peak', 6.0, 'kind_score', 0.7, 'side_score', 0.5, 'observed_at', now()))),
  array['e1000000-0000-0000-0000-000000000001', 'e1000000-0000-0000-0000-000000000002']::uuid[],
  'an old (1.7) payload is accepted as before');
select throws_ok($$ select public.backfill_bump_severity() $$, '42501', null, 'clients cannot run the backfill');
set local request.jwt.claims = '{"sub":"e0000000-0000-0000-0000-000000000002","role":"authenticated"}';
select is(cardinality(public.submit_observations(jsonb_build_array(
    jsonb_build_object('client_obs_id', 'e2000000-0000-0000-0000-000000000001', 'kind', 'jolt', 'lat', 23.3, 'lon', 28.3,
      'heading', 90, 'peak', 3.0, 'sev_index', 6.0, 'axle', 0.8, 'schema', 2, 'observed_at', now()),
    jsonb_build_object('client_obs_id', 'e2000000-0000-0000-0000-000000000002', 'kind', 'known_hit', 'lat', 23.1,
      'lon', 28.14, 'heading', 90, 'peak', 2.0, 'sev_index', 2.0, 'axle', 0.1, 'schema', 2, 'observed_at', now()),
    jsonb_build_object('client_obs_id', 'e2000000-0000-0000-0000-000000000003', 'kind', 'jolt', 'lat', 23.4, 'lon', 28.4,
      'heading', 90, 'peak', 2.0, 'sev_index', 2.0, 'schema', 2, 'observed_at', now()),
    jsonb_build_object('client_obs_id', 'e2000000-0000-0000-0000-000000000004', 'kind', 'jolt', 'lat', 23.40001,
      'lon', 28.4, 'heading', 90, 'peak', 2.0, 'sev_index', 2.0, 'schema', 2, 'observed_at', now()),
    jsonb_build_object('client_obs_id', 'e2000000-0000-0000-0000-000000000005', 'kind', 'jolt', 'lat', 23.5, 'lon', 28.5,
      'heading', 90, 'peak', 2.0, 'sev_index', 2.0, 'axle', 0.9, 'schema', 2, 'observed_at', now())))),
  5, 'a v2 payload (sev_index, axle, schema) is accepted');
select throws_ok($$ select public.submit_observations(jsonb_build_array(jsonb_build_object('client_obs_id', gen_random_uuid(),
       'kind', 'jolt', 'lat', 24, 'lon', 29, 'axle', 1.5, 'observed_at', now()))) $$,
  '22023', null, 'an axle score above 1 rejects the batch');
select throws_ok($$ select public.submit_observations(jsonb_build_array(jsonb_build_object('client_obs_id', gen_random_uuid(),
       'kind', 'jolt', 'lat', 24, 'lon', 29, 'sev_index', -1, 'observed_at', now()))) $$,
  '22023', null, 'a negative severity index rejects the batch');
select throws_ok($$ select public.submit_observations(jsonb_build_array(jsonb_build_object('client_obs_id', gen_random_uuid(),
       'kind', 'jolt', 'lat', 24, 'lon', 29, 'schema', 0, 'observed_at', now()))) $$,
  '22023', null, 'schema 0 rejects the batch');

-- ---------------------------------------------------------------- aggregation (as postgres)
reset role;
select results_eq(
  $$ select schema, sev_index, axle from public.observations
     where client_obs_id in ('e1000000-0000-0000-0000-000000000001', 'e2000000-0000-0000-0000-000000000001')
     order by client_obs_id $$,
  $$ values (1::smallint, null::real, null::real), (2::smallint, 6.0::real, 0.8::real) $$,
  'an old payload is stored as schema 1 without severity index or axle score; a v2 payload keeps them');
select is(public.aggregate_observations(), 7, 'all seven observations are merged');
-- New spots: A (1.7 jolt, peak 7.5), C (v2, index 6.0, axle 0.8), E (v2, one phone twice), G (v2, index 2.0, axle 0.9).
create temp table at_spot on commit drop as
select p.name, s.*
from (values ('A', 23.2, 28.2), ('C', 23.3, 28.3), ('E', 23.4, 28.4), ('G', 23.5, 28.5)) p(name, lat, lon)
join public.spots s
  on extensions.st_dwithin(s.geom, extensions.st_setsrid(extensions.st_makepoint(p.lon, p.lat), 4326)::extensions.geography, 15);

select results_eq($$ select kind, side, legacy_pothole, severity_band, confidence from at_spot where name = 'A' $$,
  $$ values ('bump'::text, null::text, false, 'strong'::text, 'soft'::text) $$,
  'an old pothole score makes no pothole any more: a strong bump with no side, soft after one hit');
select is((select count(*) from public.spots where kind = 'pothole' and not legacy_pothole), 0::bigint,
  'only legacy spots are still potholes');
select results_eq($$ select legacy_pothole, confidence, n_hits from public.spots where id = 900001 $$,
  $$ values (true, 'soft'::text, 6) $$, 'hits from 1.7 phones leave a legacy pothole soft, however many');
select results_eq($$ select legacy_pothole, kind, side, confidence from public.spots where id = 900005 $$,
  $$ values (false, 'bump'::text, null::text, 'full'::text) $$,
  'a hit from a v2 phone clears the legacy flag: an ordinary bump, full with its second hit');
select results_eq(
  $$ select name, round(severity::numeric, 1), severity_band, axle_hits, confidence from at_spot
     where name in ('C', 'G') order by name $$,
  $$ values ('C'::text, 6.0, 'strong'::text, 1, 'full'::text), ('G', 2.0, 'mild', 1, 'soft') $$,
  'one hit with both axles felt makes a strong bump full (severity from sev_index, not the peak), a mild one not');
select results_eq($$ select n_devices, n_hits, confidence from at_spot where name = 'E' $$,
  $$ values (1, 2, 'full'::text) $$, 'two hits from one phone make a bump full: total hits, not devices');
select is(pg_get_function_result('public.spots_near(double precision, double precision, integer)'::regprocedure),
  'TABLE(id bigint, latitude double precision, longitude double precision, heading smallint, kind text, side text, '
    'severity real, n_devices integer, last_hit timestamp with time zone)',
  'spots_near keeps its columns for 1.7.x and E1 builds');

-- ---------------------------------------------------------------- spots_near and spots_near_v2 (signed in)
set local role authenticated;
select results_eq(
  $$ select id, kind, side from public.spots_near(23.1, 28.12, 10000) where id between 900001 and 900005 order by id $$,
  $$ values (900001::bigint, 'pothole'::text, 'right'::text), (900002, 'bump', 'both'), (900003, 'bump', 'left'),
            (900005, 'bump', null) $$,
  'spots_near says pothole only for a legacy spot; side as stored');
select is((select array_agg(k order by k collate "C") from public.spots_near_v2(23.1, 28.12, 10000) r,
           jsonb_object_keys(to_jsonb(r)) k where r.id = 900001),
          array['confidence', 'heading', 'id', 'lat', 'legacy', 'lon', 'n_devices', 'n_hits', 'severity', 'severity_band'],
          'spots_near_v2 returns these fields');
select results_eq(
  $$ select id, round(lat::numeric, 4), round(lon::numeric, 4), legacy, confidence, severity_band, n_devices, n_hits
     from public.spots_near_v2(23.1, 28.12, 10000) where id in (900001, 900005) order by id $$,
  $$ values (900001::bigint, 23.1, 28.1, true, 'soft'::text, 'strong'::text, 2, 6),
            (900005, 23.1, 28.14, false, 'full', 'mild', 2, 2) $$,
  'spots_near_v2 gives the legacy flag, confidence, band and total hits');
select is((select array_agg(id order by id) from public.spots_near_v2(23.1, 28.1, 1000000)),
          array[900001, 900002, 900003, 900005]::bigint[],
          'spots_near_v2 filters like spots_near: confirmed spots only, radius capped at 10 km');
reset role;
set local role anon;
select throws_ok($$ select * from public.spots_near_v2(23.1, 28.1) $$, '42501', null, 'anon cannot call spots_near_v2');

-- ---------------------------------------------------------------- training samples take the bands (issue #114)
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub":"e0000000-0000-0000-0000-000000000001","role":"authenticated"}';
do $$ begin perform public.set_training_consent(true, 1); end $$;
select is(jsonb_array_length(public.submit_training_samples(jsonb_build_object('app_version', '2.0.0', 'sdk', 34,
    'samples', jsonb_build_array(public.t_band_sample('f0000000-0000-0000-0000-000000000001', 'mild'),
      public.t_band_sample('f0000000-0000-0000-0000-000000000002', 'moderate'),
      public.t_band_sample('f0000000-0000-0000-0000-000000000003', 'strong'),
      public.t_band_sample('f0000000-0000-0000-0000-000000000004', 'pothole')))) -> 'samples'), 4,
  'training samples take the severity band as classification, and still the old values');
select throws_ok($$ select public.submit_training_samples(jsonb_build_object('app_version', '2.0.0', 'sdk', 34,
       'samples', jsonb_build_array(public.t_band_sample('f0000000-0000-0000-0000-000000000005', 'huge')))) $$,
  '22023', null, 'an unknown classification still rejects the batch');
reset role;
select lives_ok($$ update public.training_samples set label = 'strong', label_source = 'manual'
                   where client_sample_id = 'f0000000-0000-0000-0000-000000000003' $$, 'hand labels may be bands too');

-- ---------------------------------------------------------------- moving the band edges
update public.app_settings set value = '6.5' where key = 'severity_strong_min';
do $$ begin perform public.backfill_bump_severity(); end $$;
select results_eq(
  $$ select s.legacy_pothole, s.severity_band, s.confidence from public.spots s
     where s.id in (900005, (select a.id from at_spot a where a.name = 'C')) order by s.id $$,
  $$ values (false, 'moderate'::text, 'soft'::text), (false, 'mild', 'full') $$,
  'a rerun after moving the strong edge to 6.5 re-bands spots (a 6.0 axle hit is soft again); a cleared legacy stays cleared');

select * from finish();
rollback;
