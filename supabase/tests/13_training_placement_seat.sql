-- Placement "seat": training samples and trips accept it through submit_training_samples; the old four still pass and
-- anything else is still refused (the whole batch, 22023).
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, aud, role, email)
values ('5ea70000-0000-0000-0000-000000000001', 'authenticated', 'authenticated', 'seat@test.local');
insert into public.devices (id) values ('5ea70000-0000-0000-0000-000000000001');

-- Payload builders (test only, rolled back). Waveform: 200 int16 values of 1 at 50 Hz.
create function public.t_seat_batch(cid text, placement text) returns jsonb language sql as $$
  select jsonb_build_object('app_version', '2.1.0', 'sdk', 34, 'brand', 'samsung',
    'samples', jsonb_build_array(jsonb_build_object(
      'client_sample_id', cid, 'day', current_date, 'decision', 'learned', 'reason', null, 'classification', 'mild',
      'placement', placement, 'speed_kmh', 20, 'rate_hz', 50, 'pre_ms', 1000,
      'accel_v', encode(decode(repeat('0100', 200), 'hex'), 'base64'))),
    'trips', jsonb_build_array(jsonb_build_object(
      'client_trip_id', cid, 'day', current_date, 'placement', placement, 'duration_s', 600, 'distance_m', 5000)));
$$;
grant execute on function public.t_seat_batch(text, text) to authenticated;

select is((select array_agg(pg_get_constraintdef(c.oid) like '%''seat''%' order by c.conrelid::regclass::text)
           from pg_constraint c
           where c.conname in ('training_samples_placement_check', 'training_trips_placement_check')),
          array[true, true], 'both placement CHECKs list seat');

set local role authenticated;
set local request.jwt.claims = '{"sub":"5ea70000-0000-0000-0000-000000000001","role":"authenticated"}';
select is(public.set_training_consent(true, 1), true, 'the device opts in');
select is(public.submit_training_samples(public.t_seat_batch('c0000000-0000-0000-0000-000000000001', 'seat')),
          '{"samples": ["c0000000-0000-0000-0000-000000000001"], "trips": ["c0000000-0000-0000-0000-000000000001"]}'::jsonb,
          'a sample and a trip on the seat are accepted');
select lives_ok($$ select public.submit_training_samples(public.t_seat_batch('c0000000-0000-0000-0000-000000000002', 'cupholder')) $$,
                'the older placements still pass');
select throws_ok($$ select public.submit_training_samples(public.t_seat_batch('c0000000-0000-0000-0000-000000000003', 'sofa')) $$,
                 '22023', null, 'an unknown placement still rejects the batch');
reset role;

select is((select array_agg(placement order by client_sample_id) from public.training_samples
           where client_sample_id::text like 'c0000000%'), array['seat', 'cupholder'], 'samples keep their placement');
select is((select array_agg(placement order by client_trip_id) from public.training_trips
           where client_trip_id::text like 'c0000000%'), array['seat', 'cupholder'], 'trips keep their placement');
select is((select count(*) from public.training_samples where client_sample_id = 'c0000000-0000-0000-0000-000000000003'),
          0::bigint, 'nothing of the refused batch is stored');

select * from finish();
rollback;
