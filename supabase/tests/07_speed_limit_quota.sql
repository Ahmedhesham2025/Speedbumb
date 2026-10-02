-- speed-limits Edge Function quota: counters only, no client access, 8 per user and 2,000 per project per UTC day.
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

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

-- ---------------------------------------------------------------- service role (the Edge Function)
reset role;
set local role service_role;
select is((select array_agg(public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111')) from generate_series(1, 8)),
          array_fill('ok'::text, array[8]), '8 calls a day are allowed per user');
select is(public.take_speed_limit_quota('11111111-1111-1111-1111-111111111111'), 'user', 'the 9th call that day is refused');
select is(public.take_speed_limit_quota('22222222-2222-2222-2222-222222222222'), 'ok', 'another user still has quota');
select throws_ok($$ select public.take_speed_limit_quota(null) $$, '22023', null, 'a user id is required');
reset role;

select is((select calls from public.speed_limit_usage
           where user_id = '11111111-1111-1111-1111-111111111111' and day = (now() at time zone 'utc')::date),
          8, 'a refused call is not counted');
select is((select calls from public.speed_limit_usage_total where day = (now() at time zone 'utc')::date),
          9, 'the project counter counts every allowed call');

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

select * from finish();
rollback;
