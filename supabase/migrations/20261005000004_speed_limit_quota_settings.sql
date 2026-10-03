-- v1.7 live speed limits: the phone now calls `speed-limits` about once per km (or on a road change) while the
-- opt-in live display/warning is on, as well as once after a trip for the score. 8 calls per user per day is far
-- too low for that, so both daily caps become app_settings knobs the owner can change with one UPDATE.
-- TomTom's free plan allows about 2,500 calls a day and blocks (never bills) above that; keep the project cap below it.
insert into public.app_settings (key, value) values ('speed_limit_user_daily', '60') on conflict (key) do nothing;
insert into public.app_settings (key, value) values ('speed_limit_project_daily', '2000') on conflict (key) do nothing;

-- Counts one call for `uid` today (UTC) if both quotas allow it, atomically under concurrent calls.
-- Returns 'ok', 'user' (app_settings.speed_limit_user_daily calls per user per day used up, default 60) or
-- 'project' (app_settings.speed_limit_project_daily calls per day used up, default 2000).
-- A setting that is missing or not a whole number falls back to its default; 0 switches the function off.
-- A refused call changes no counter.
create or replace function public.take_speed_limit_quota(uid uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  today date := (now() at time zone 'utc')::date;
  user_cap int;
  project_cap int;
  n int;
begin
  if uid is null then
    raise exception 'user required' using errcode = '22023';
  end if;
  select coalesce((select case when a.value ~ '^[0-9]{1,7}$' then a.value::int end
                   from public.app_settings a where a.key = 'speed_limit_user_daily'), 60)
  into user_cap;
  select coalesce((select case when a.value ~ '^[0-9]{1,7}$' then a.value::int end
                   from public.app_settings a where a.key = 'speed_limit_project_daily'), 2000)
  into project_cap;
  -- A cap of 0 must refuse even the first call of the day (the insert below would otherwise create the row at 1).
  if user_cap < 1 then
    return 'user';
  end if;
  begin
    -- The conditional upsert locks the row, so two parallel calls cannot both take the last slot.
    insert into public.speed_limit_usage as u (day, user_id, calls) values (today, uid, 1)
    on conflict (day, user_id) do update set calls = u.calls + 1 where u.calls < user_cap
    returning u.calls into n;
    if n is null then
      return 'user';
    end if;
    n := null;
    if project_cap >= 1 then
      insert into public.speed_limit_usage_total as t (day, calls) values (today, 1)
      on conflict (day) do update set calls = t.calls + 1 where t.calls < project_cap
      returning t.calls into n;
    end if;
    if n is null then
      -- Undo the user's increment above (the block is a subtransaction).
      raise exception 'project quota' using errcode = 'P0001';
    end if;
  exception when sqlstate 'P0001' then
    return 'project';
  end;
  return 'ok';
end;
$$;

-- Same grants as before (CREATE OR REPLACE keeps them; restated so this file is safe on its own).
revoke execute on function public.take_speed_limit_quota(uuid) from public, anon, authenticated;
grant execute on function public.take_speed_limit_quota(uuid) to service_role;
