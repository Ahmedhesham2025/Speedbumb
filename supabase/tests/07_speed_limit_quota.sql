-- speed-limits Edge Function quota: counters only, no client access; per UTC day 60 per user and 2,000 per project
-- by default, both read from app_settings (speed_limit_user_daily, speed_limit_project_daily).
begin;
create extension if not exists pgtap with schema extensions;
select plan(30);

insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local');

-- ---------------------------------------------------------------- shape: counters only, RLS on
select has_table('public', 'speed_limit_usage', 'per-user counter table exists');
select columns_are('public', 'speed_limit_usage', array['day', 'user_id', 'calls'], 'it holds counts only (no coordinates, no limits)');
select columns_are('public', 'speed_limit_usage_total', array['day', 'calls'], 'the project counter holds counts only');
select is((select bool_and(relrowsecurity) from pg_class
           where oid in ('public.speed_limit_usage'::regclass, 'public.speed_limit_usage_total'::regclass)),
          true, 'RLS is on for both counter tables');

-- ---------------------------------------------------------------- clients: no access at all
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select throws_ok($$ select * from public.speed_limit_usage $$, '42501', null, 'a user cannot read counters');
select throws_ok($$ insert into public.speed_limit_usage (day, user_id, calls) values (current_date, auth.uid(), 0) $$,
                 '42501', null, 'a user cannot write counters');
select throws_ok($$ select * from public.speed_limit_usage_total $$, '42501', null, 'a user cannot read the project counter');
select throws_ok($$ select public.take_speed_limit_quota(auth.uid()) $$, '42501', null, 'a user cannot spend quota directly');
select throws_ok($$ select public.prune_speed_limit_usage() $$, '42501', null, 'a user cannot prune');
reset role;
set local role anon;
select throws_ok($$ select public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111') $$, '42501', null, 'anon cannot spend quota');

-- ---------------------------------------------------------------- default caps
reset role;
select is(array[(select value from public.app_settings where key = 'speed_limit_user_daily'),
                (select value from public.app_settings where key = 'speed_limit_project_daily')],
          array['60', '2000'], 'the caps ship as 60 per user and 2,000 per project');

-- ---------------------------------------------------------------- service role (the Edge Function)
set local role service_role;
select is((select array_agg(public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111')) from generate_series(1, 60)),
          array_fill('ok'::text, array[60]), '60 calls a day are allowed per user');
select is(public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111'), 'user', 'the 61st call that day is refused');
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'ok', 'another user still has quota');
select throws_ok($$ select public.take_speed_limit_quota(null) $$, '22023', null, 'a user id is required');
reset role;

select is((select calls from public.speed_limit_usage
           where user_id = '11111111-1111-1111-1111-111111111111' and day = (now() at time zone 'utc')::date),
          60, 'a refused call is not counted');
select is((select calls from public.speed_limit_usage_total where day = (now() at time zone 'utc')::date),
          61, 'the project counter counts every allowed call');

-- Project quota: pretend 2,000 calls were made today.
update public.speed_limit_usage_total set calls = 2000 where day = (now() at time zone 'utc')::date;
set local role service_role;
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'project', 'call 2,001 of the day is refused for everyone');
reset role;
select is((select calls from public.speed_limit_usage
           where user_id = '22222222-2222-2222-2222-222222222222' and day = (now() at time zone 'utc')::date),
          1, 'a project-refused call does not use up the user''s quota');

-- ---------------------------------------------------------------- pruning (30 days)
insert into public.speed_limit_usage (day, user_id, calls) values
  ((now() at time zone 'utc')::date - 31, '11111111-1111-1111-1111-111111111111', 3);
insert into public.speed_limit_usage_total (day, calls) values ((now() at time zone 'utc')::date - 31, 3);
select lives_ok($$ select public.prune_speed_limit_usage() $$, 'the prune job runs');
select is(array[(select count(*)::int from public.speed_limit_usage where day < (now() at time zone 'utc')::date - 30),
                (select count(*)::int from public.speed_limit_usage_total where day < (now() at time zone 'utc')::date - 30),
                (select count(*)::int from public.speed_limit_usage where day = (now() at time zone 'utc')::date)],
          array[0, 0, 2], 'counters older than 30 days are pruned, today''s are kept');

-- ---------------------------------------------------------------- the caps follow app_settings
-- Today so far: user 1 has 60 calls, user 2 has 1, the project 2,000.
update public.app_settings set value = '2001' where key = 'speed_limit_project_daily';
set local role service_role;
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'ok', 'raising the project setting lets calls through again');
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'project', 'the new project cap is enforced');
reset role;
update public.app_settings set value = '5000' where key = 'speed_limit_project_daily';
update public.app_settings set value = '2' where key = 'speed_limit_user_daily';
set local role service_role;
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'user', 'lowering the user setting refuses at once');
reset role;
update public.app_settings set value = '3' where key = 'speed_limit_user_daily';
set local role service_role;
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'ok', 'raising the user setting allows one more call');
reset role;
update public.app_settings set value = 'lots' where key = 'speed_limit_user_daily';
set local role service_role;
select is(array[public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111'),
                public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222')],
          array['user', 'ok'], 'an invalid user setting falls back to 60');
reset role;
delete from public.app_settings where key in ('speed_limit_user_daily', 'speed_limit_project_daily');
set local role service_role;
select is(array[public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111'),
                public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222')],
          array['user', 'project'], 'missing settings fall back to 60 per user and 2,000 per project');
reset role;
insert into public.app_settings (key, value) values ('speed_limit_user_daily', '0'), ('speed_limit_project_daily', '5000');
set local role service_role;
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'user', 'a user cap of 0 refuses every call');
reset role;
update public.app_settings set value = '60' where key = 'speed_limit_user_daily';
update public.app_settings set value = '0' where key = 'speed_limit_project_daily';
set local role service_role;
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'project', 'a project cap of 0 refuses every call');
reset role;
select is((select calls from public.speed_limit_usage
           where user_id = '22222222-2222-2222-2222-222222222222' and day = (now() at time zone 'utc')::date),
          4, 'refused calls under changed settings are not counted');

select * from finish();
rollback;
