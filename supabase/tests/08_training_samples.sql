-- "Help improve detection": consent gating, validation, caps, size guard, withdrawal, forget_me, RLS deny, retention.
begin;
create extension if not exists pgtap with schema extensions;
select plan(43);

insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local'),
  ('33333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', 'c@test.local');
insert into public.devices (id) values
  ('11111111-1111-1111-1111-111111111111'), ('22222222-2222-2222-2222-222222222222');
insert into public.spots (id, geom, heading, status) overriding system value
values (900001, 'SRID=4326;POINT(28.4 23.4)', 90, 'confirmed');
update public.app_settings set value = '200' where key = 'training_max_mb';

-- Payload builders (test only, rolled back). Waveforms: 200 int16 values of 1 (= 0.004 m/s², 0.001 rad/s) at 50 Hz.
create function public.t_sample(cid text, decision text, extra jsonb default '{}') returns jsonb language sql as $$
  select jsonb_build_object(
    'client_sample_id', cid, 'client_trip_id', 'b0000000-0000-0000-0000-000000000001', 'day', current_date,
    'decision', decision, 'reason', null, 'classification', 'bump', 'placement', 'mounted', 'speed_kmh', 32.5,
    'heading_change_deg', 2, 'gps_accuracy_m', 5, 'peak', 6.1, 'shape_score', -0.4, 'first_down', false,
    'roll_pitch_ratio', 0.6, 'side_score', 0, 'spot_id', null, 'lat', 30.0123, 'lon', 31.2345, 'rate_hz', 50,
    'pre_ms', 2000, 'accel_v', encode(decode(repeat('0100', 200), 'hex'), 'base64'),
    'gyro_roll', encode(decode(repeat('0100', 200), 'hex'), 'base64'),
    'gyro_pitch', encode(decode(repeat('0100', 200), 'hex'), 'base64')) || extra;
$$;
create function public.t_batch(samples jsonb, trips jsonb default '[]') returns jsonb language sql as $$
  select jsonb_build_object('app_version', '1.4.0', 'sdk', 34, 'brand', 'Samsung', 'samples', samples, 'trips', trips);
$$;
create table public.t_payload (name text primary key, body jsonb);
grant select on public.t_payload to authenticated;
insert into public.t_payload values
  ('one', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000001', 'learned')),
     jsonb_build_array(jsonb_build_object(
       'client_trip_id', 'b0000000-0000-0000-0000-000000000001', 'day', current_date, 'placement', 'mounted',
       'duration_s', 3000, 'distance_m', 25000, 'n_learned', 3, 'n_hit', 10, 'n_rejected', 5, 'n_miss', 1,
       'n_pass_clear', 4, 'n_user_mute', 1, 'n_beeps', 12, 'battery_start', 80, 'battery_end', 71, 'score', 88,
       'speeding_s', 40, 'harsh_brakes', 1, 'harsh_accels', 0, 'harsh_corners', 2, 'swerves', 0, 'bumps_fast', 1,
       'phone_use', 0)))),
  ('spot_and_mute', public.t_batch(jsonb_build_array(
     public.t_sample('a0000000-0000-0000-0000-000000000002', 'hit', '{"spot_id": 900001}'),
     public.t_sample('a0000000-0000-0000-0000-000000000003', 'user_mute')))),
  ('two_new', public.t_batch(jsonb_build_array(
     public.t_sample('a0000000-0000-0000-0000-000000000004', 'rejected', '{"reason": "too_slow"}'),
     public.t_sample('a0000000-0000-0000-0000-000000000005', 'miss')))),
  ('one_new', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000006', 'pass_clear')))),
  ('trip_new', public.t_batch('[]', jsonb_build_array(jsonb_build_object(
     'client_trip_id', 'b0000000-0000-0000-0000-000000000002', 'day', current_date, 'placement', 'pocket',
     'duration_s', 60, 'distance_m', 500)))),
  ('bad_decision', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000009', 'crash')))),
  ('bad_short', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000009', 'hit',
     jsonb_build_object('accel_v', encode(decode(repeat('0100', 5), 'hex'), 'base64'), 'gyro_roll', null, 'gyro_pitch', null))))),
  ('bad_base64', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000009', 'hit',
     '{"accel_v": "not base64 !!"}')))),
  ('bad_gyro', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000009', 'hit',
     '{"gyro_pitch": null}')))),
  ('bad_lat', public.t_batch(jsonb_build_array(public.t_sample('a0000000-0000-0000-0000-000000000009', 'hit',
     '{"lat": 45.1}')))),
  ('too_many', public.t_batch((select jsonb_agg(public.t_sample(gen_random_uuid()::text, 'hit')) from generate_series(1, 101))));

-- ---------------------------------------------------------------- shape
select has_column('public', 'devices', 'training_consent', 'devices record the training consent');
select is((select bool_and(relrowsecurity) from pg_class
           where oid in ('public.training_samples'::regclass, 'public.training_trips'::regclass, 'public.training_quota'::regclass)),
          true, 'RLS is on for every training table');

-- ---------------------------------------------------------------- consent gating and RLS deny (device A)
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'one')) $$,
                 '42501', null, 'no samples without training consent');
select throws_ok($$ update public.devices set training_consent = true $$, '42501', null,
                 'consent cannot be set by writing the devices row');
select is(public.set_training_consent(true), true, 'a device can opt in to help improve detection');
select is(public.submit_training_samples((select body from public.t_payload where name = 'one')),
          '{"samples": ["a0000000-0000-0000-0000-000000000001"], "trips": ["b0000000-0000-0000-0000-000000000001"]}'::jsonb,
          'with consent a sample and a trip are accepted and their ids returned');
select lives_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'one')) $$,
                'a repeated batch is ignored, not an error');
select lives_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'spot_and_mute')) $$,
                'a sample on a shared spot and a user mute are accepted');
select throws_ok($$ select * from public.training_samples $$, '42501', null, 'a device cannot read samples');
select throws_ok($$ insert into public.training_samples (device_id) values (auth.uid()) $$, '42501', null,
                 'a device cannot insert samples directly');
select throws_ok($$ select * from public.training_trips $$, '42501', null, 'a device cannot read trip summaries');
select throws_ok($$ select * from public.training_quota $$, '42501', null, 'a device cannot read its counters');
select throws_ok($$ select * from public.training_export() $$, '42501', null, 'a device cannot export');
select throws_ok($$ select public.prune_training_data() $$, '42501', null, 'a device cannot prune');

-- ---------------------------------------------------------------- validation: the whole batch is rejected
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'bad_decision')) $$,
                 '22023', null, 'an unknown decision is rejected');
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'bad_short')) $$,
                 '22023', null, 'a window shorter than 2 s is rejected');
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'bad_base64')) $$,
                 '22023', null, 'a waveform that is not base64 is rejected');
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'bad_gyro')) $$,
                 '22023', null, 'gyro roll without gyro pitch is rejected');
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'bad_lat')) $$,
                 '22023', null, 'a position outside the region is rejected');
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'too_many')) $$,
                 '22023', null, 'more than 100 samples in one batch is rejected');
reset role;

-- ---------------------------------------------------------------- what was stored
select is((select array[count(*) filter (where s.device_id = '11111111-1111-1111-1111-111111111111'),
                        (select count(*) from public.training_trips)] from public.training_samples s),
          array[3, 1]::bigint[], 'three samples and one trip are stored, the repeat was ignored');
select is((select array[round(lat_1km::numeric, 2), round(lon_1km::numeric, 2)] from public.training_samples
           where client_sample_id = 'a0000000-0000-0000-0000-000000000001'),
          array[30.01, 31.23], 'a sample without a shared spot keeps only a ~1 km cell');
select is((select array[spot_id::text, coalesce(lat_1km::text, 'none')] from public.training_samples
           where client_sample_id = 'a0000000-0000-0000-0000-000000000002'),
          array['900001', 'none'], 'a sample on a shared spot keeps the spot id and no position');
select is((select array_agg(label_source order by client_sample_id) from public.training_samples),
          array['engine', 'engine', 'user_mute'], 'user mutes are labelled as such, the rest by the engine');
select is((select array[samples, bytes, trips] from public.training_quota
           where device_id = '11111111-1111-1111-1111-111111111111' and day = current_date),
          array[3, 3600, 1], 'the daily counters hold 3 samples, 3 x 1200 waveform bytes and 1 trip');
select is((select array_agg(round(v::numeric, 3) order by i)
           from unnest(public.training_i16(decode('0100ffffe803', 'hex'), 0.004)) with ordinality u(v, i)),
          array[0.004, -0.004, 4.000], 'int16 little-endian windows decode to physical units');
set local role service_role;
select is((select array[count(*)::numeric, round(min(accel_v[1])::numeric, 3), max(array_length(gyro_roll, 1))::numeric]
           from public.training_export()),
          array[3, 0.004, 200]::numeric[], 'the owner export returns decoded windows (service role)');
reset role;

-- ---------------------------------------------------------------- caps: 300 samples, 1 MB, 20 trips per device per day
update public.training_quota set samples = 299 where device_id = '11111111-1111-1111-1111-111111111111';
set local role authenticated;
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'two_new')) $$,
                 '54000', null, 'sample 301 of the day is refused');
reset role;
select is((select count(*) from public.training_samples), 3::bigint, 'a refused batch stores nothing');
update public.training_quota set samples = 0, bytes = 999000 where device_id = '11111111-1111-1111-1111-111111111111';
set local role authenticated;
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'one_new')) $$,
                 '54000', null, 'more than 1 MB of waveforms a day is refused');
reset role;
update public.training_quota set bytes = 0, trips = 20 where device_id = '11111111-1111-1111-1111-111111111111';
set local role authenticated;
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'trip_new')) $$,
                 '54000', null, 'trip summary 21 of the day is refused');
reset role;

-- ---------------------------------------------------------------- project size guard
update public.training_quota set trips = 0 where device_id = '11111111-1111-1111-1111-111111111111';
update public.app_settings set value = '0' where key = 'training_max_mb';
set local role authenticated;
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'one_new')) $$,
                 '53100', null, 'no new samples once the training tables reach training_max_mb');
reset role;
update public.app_settings set value = '200' where key = 'training_max_mb';

-- ---------------------------------------------------------------- unregistered and anon
set local role authenticated;
set local request.jwt.claims = '{"sub":"33333333-3333-3333-3333-333333333333","role":"authenticated"}';
select throws_ok($$ select public.set_training_consent(true) $$, '42501', null, 'an unregistered device cannot opt in');
reset role;
set local role anon;
select throws_ok($$ select public.submit_training_samples('{}') $$, '42501', null, 'anon cannot submit');

-- ---------------------------------------------------------------- withdrawal deletes at once (device B)
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select lives_ok($$ select public.set_training_consent(true) $$, 'device B opts in');
select lives_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'one')) $$,
                'device B uploads a sample and a trip');
select is(public.set_training_consent(false), false, 'device B withdraws consent');
select throws_ok($$ select public.submit_training_samples((select body from public.t_payload where name = 'one_new')) $$,
                 '42501', null, 'after withdrawal nothing is accepted');
reset role;
select is((select array[(select count(*) from public.training_samples where device_id = '22222222-2222-2222-2222-222222222222'),
                        (select count(*) from public.training_trips where device_id = '22222222-2222-2222-2222-222222222222'),
                        (select count(*) from public.training_samples where device_id = '11111111-1111-1111-1111-111111111111')]),
          array[0, 0, 3]::bigint[], 'withdrawal deletes all of B''s samples and trips, A''s stay');
select is((select training_consent::text || ',' || (training_consent_at is not null)::text from public.devices
           where id = '22222222-2222-2222-2222-222222222222'), 'false,true', 'the withdrawal is recorded with a time');

-- ---------------------------------------------------------------- forget_me (device A)
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select lives_ok($$ select public.forget_me() $$, 'device A asks to be forgotten');
reset role;
select is((select array[(select count(*) from public.training_samples where device_id = '11111111-1111-1111-1111-111111111111'),
                        (select count(*) from public.training_trips where device_id = '11111111-1111-1111-1111-111111111111'),
                        (select count(*) from public.training_quota where device_id = '11111111-1111-1111-1111-111111111111')]),
          array[0, 0, 0]::bigint[], 'forget_me deletes all training rows and counters');

-- ---------------------------------------------------------------- retention: 12 months
insert into public.training_samples
  (device_id, client_sample_id, received_on, day, decision, label_source, placement, speed_kmh, sdk, app_version,
   rate_hz, pre_ms, accel_v)
select '22222222-2222-2222-2222-222222222222', gen_random_uuid(), d, d, 'hit', 'engine', 'mounted', 30, 34, '1.4.0',
       50, 2000, decode(repeat('0100', 200), 'hex')
from unnest(array[current_date - 400, current_date - 10]) d;
insert into public.training_trips (device_id, client_trip_id, received_on, day, placement, sdk, app_version, duration_s, distance_m)
select '22222222-2222-2222-2222-222222222222', gen_random_uuid(), d, d, 'mounted', 34, '1.4.0', 60, 500
from unnest(array[current_date - 400, current_date - 10]) d;
insert into public.training_quota (device_id, day, samples) values ('22222222-2222-2222-2222-222222222222', current_date - 5, 1);
do $$ begin perform public.prune_training_data(); end $$;
select is((select array[(select count(*) from public.training_samples), (select count(*) from public.training_trips),
                        (select count(*) from public.training_quota where day < current_date - 2)]),
          array[1, 1, 0]::bigint[], 'samples and trips older than 12 months and old counters are pruned, recent ones stay');

select * from finish();
rollback;
