-- Fleet roles: owners control everything, admins can't take over, viewers and outsiders can't write,
-- and a fleet always keeps an owner.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, aud, role, email) values
  ('0aaaaaaa-0000-0000-0000-000000000001', 'authenticated', 'authenticated', 'owner@test.local'),
  ('0aaaaaaa-0000-0000-0000-000000000002', 'authenticated', 'authenticated', 'admin@test.local'),
  ('0aaaaaaa-0000-0000-0000-000000000003', 'authenticated', 'authenticated', 'viewer@test.local'),
  ('0aaaaaaa-0000-0000-0000-000000000004', 'authenticated', 'authenticated', 'outsider@test.local'),
  ('0aaaaaaa-0000-0000-0000-000000000005', 'authenticated', 'authenticated', 'new@test.local');
insert into public.fleets (id, name) overriding system value values (900001, 'Roles fleet');
insert into public.memberships (user_id, fleet_id, role) values
  ('0aaaaaaa-0000-0000-0000-000000000001', 900001, 'owner'),
  ('0aaaaaaa-0000-0000-0000-000000000002', 900001, 'admin'),
  ('0aaaaaaa-0000-0000-0000-000000000003', 900001, 'viewer');

-- ---------------------------------------------------------------- admin
set local role authenticated;
set local request.jwt.claims = '{"sub":"0aaaaaaa-0000-0000-0000-000000000002","role":"authenticated"}';

update public.memberships set role = 'owner' where user_id = '0aaaaaaa-0000-0000-0000-000000000002';
select is((select role from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000002'), 'admin',
  'admin cannot promote themselves');
select throws_ok(
  $$ update public.memberships set role = 'owner' where user_id = '0aaaaaaa-0000-0000-0000-000000000003' $$,
  '42501', null, 'admin cannot promote someone else to owner');
select throws_ok(
  $$ insert into public.memberships (user_id, fleet_id, role) values ('0aaaaaaa-0000-0000-0000-000000000005', 900001, 'owner') $$,
  '42501', null, 'admin cannot create an owner');
update public.memberships set role = 'viewer' where user_id = '0aaaaaaa-0000-0000-0000-000000000001';
select is((select role from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000001'), 'owner',
  'admin cannot demote the owner');
delete from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000001';
select is((select count(*) from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000001'), 1::bigint,
  'admin cannot remove the owner');
delete from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000002';
select is((select count(*) from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000002'), 1::bigint,
  'admin cannot remove their own membership');
delete from public.fleets where id = 900001;
select is((select count(*) from public.fleets where id = 900001), 1::bigint, 'admin cannot delete the fleet');
select lives_ok(
  $$ insert into public.memberships (user_id, fleet_id, role) values ('0aaaaaaa-0000-0000-0000-000000000005', 900001, 'viewer') $$,
  'admin can add a viewer');

-- ---------------------------------------------------------------- viewer
set local request.jwt.claims = '{"sub":"0aaaaaaa-0000-0000-0000-000000000003","role":"authenticated"}';
select throws_ok(
  $$ insert into public.memberships (user_id, fleet_id, role) values ('0aaaaaaa-0000-0000-0000-000000000004', 900001, 'viewer') $$,
  '42501', null, 'viewer cannot add members');
select throws_ok($$ insert into public.invites (fleet_id) values (900001) $$, '42501', null, 'viewer cannot create invites');
select throws_ok($$ insert into public.drivers (fleet_id, display_name) values (900001, 'X') $$, '42501', null, 'viewer cannot add drivers');

-- ---------------------------------------------------------------- outsider
set local request.jwt.claims = '{"sub":"0aaaaaaa-0000-0000-0000-000000000004","role":"authenticated"}';
select throws_ok(
  $$ insert into public.memberships (user_id, fleet_id, role) values ('0aaaaaaa-0000-0000-0000-000000000004', 900001, 'viewer') $$,
  '42501', null, 'a non-member cannot join a fleet by themselves');

-- ---------------------------------------------------------------- owner (the only one)
set local request.jwt.claims = '{"sub":"0aaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}';
select throws_ok(
  $$ delete from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000001' $$,
  '23514', null, 'the last owner cannot leave');
select throws_ok(
  $$ update public.memberships set role = 'admin' where user_id = '0aaaaaaa-0000-0000-0000-000000000001' $$,
  '23514', null, 'the last owner cannot demote themselves');
select lives_ok(
  $$ update public.memberships set role = 'owner' where user_id = '0aaaaaaa-0000-0000-0000-000000000002' $$,
  'an owner can promote an admin to owner');
select lives_ok(
  $$ delete from public.memberships where user_id = '0aaaaaaa-0000-0000-0000-000000000001' $$,
  'an owner can leave once another owner exists');

-- ---------------------------------------------------------------- new owner deletes the fleet
set local request.jwt.claims = '{"sub":"0aaaaaaa-0000-0000-0000-000000000002","role":"authenticated"}';
select lives_ok($$ delete from public.fleets where id = 900001 $$, 'an owner can delete the fleet');
reset role;
select is((select count(*) from public.memberships where fleet_id = 900001), 0::bigint,
  'deleting the fleet removes all memberships, last owner included');

select * from finish();
rollback;
