-- Write limits (nobody can fill the free database) and same-fleet links.
begin;
create extension if not exists pgtap with schema extensions;
select plan(15);

insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'authenticated', 'authenticated', 'admin@test.local');
insert into public.devices (id, share_enabled) values ('11111111-1111-1111-1111-111111111111', true);
insert into public.fleets (id, name) overriding system value values (900001, 'Mine'), (900002, 'Other');
insert into public.memberships (user_id, fleet_id, role) values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 900001, 'admin');
insert into public.vehicles (id, fleet_id, label) overriding system value values (900001, 900001, 'Mine'), (900002, 900002, 'Other');
insert into public.drivers (id, fleet_id, display_name) overriding system value values (900001, 900001, 'Mine'), (900002, 900002, 'Other');

-- ---------------------------------------------------------------- device
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';

select throws_ok($$ select public.register_device(repeat('9', 41), 34, 1, true) $$, '22023', null, 'app_version over 40 chars is rejected');
select throws_ok($$ select public.register_device('1.0', 0, 1, true) $$, '22023', null, 'an impossible os_api is rejected');
select throws_ok($$ select public.submit_crash_report('1.0', 'Pixel', repeat('x', 8193)) $$, '22023', null, 'a stack over 8 KB is rejected');
select throws_ok($$ select public.submit_crash_report('1.0', repeat('m', 41), 'trace') $$, '22023', null, 'a model over 40 chars is rejected');
select lives_ok($$ select public.submit_crash_report('1.0', 'Pixel', 'trace') from generate_series(1, 20) $$, '20 crash reports a day are fine');
select throws_ok($$ select public.submit_crash_report('1.0', 'Pixel', 'trace') $$, '54000', null, 'the 21st crash report that day is refused');

set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select throws_ok($$ select public.submit_crash_report('1.0', 'Pixel', 'trace') $$, '42501', null, 'an unregistered user cannot file crash reports');

-- ---------------------------------------------------------------- fleet admin
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';

select throws_ok($$ insert into public.vehicles (fleet_id, label) values (900001, repeat('v', 81)) $$, '23514', null, 'vehicle labels are at most 80 chars');
select throws_ok($$ update public.fleets set name = repeat('f', 81) where id = 900001 $$, '23514', null, 'fleet names are at most 80 chars');
select throws_ok($$ insert into public.drivers (fleet_id, display_name) values (900001, '') $$, '23514', null, 'driver names cannot be empty');
select throws_ok(
  $$ insert into public.drivers (fleet_id, display_name, vehicle_id) values (900001, 'Borrower', 900002) $$,
  '23503', null, 'a driver cannot use another fleet''s vehicle');
select throws_ok(
  $$ insert into public.invites (fleet_id, driver_id) values (900001, 900002) $$,
  '23503', null, 'an invite cannot point to another fleet''s driver');
select throws_ok(
  $$ insert into public.invites (code, fleet_id) values ('my-chosen-code-123', 900001) $$,
  '42501', null, 'clients cannot choose invite codes');
select lives_ok($$ insert into public.invites (fleet_id, driver_id) values (900001, 900001) $$, 'admin can create an invite');
select is((select length(code) from public.invites where fleet_id = 900001), 32, 'the server generates a 32-char random code');

reset role;
select * from finish();
rollback;
