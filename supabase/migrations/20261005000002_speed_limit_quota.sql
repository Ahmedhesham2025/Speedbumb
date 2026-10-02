-- Daily call counters for the `speed-limits` Edge Function (TomTom Snap to Roads, Freemium plan).
-- Only counts live here: TomTom T&C 11.4 forbids storing or caching its results server-side,
-- so there is deliberately no table of speed limits, roads or coordinates.
-- No client access at all: the function calls take_speed_limit_quota() with the service role.

create table public.speed_limit_usage (
  day date not null,
  user_id uuid not null references auth.users (id) on delete cascade,
  calls int not null default 0 check (calls >= 0),
  primary key (day, user_id)
);
create index speed_limit_usage_user_idx on public.speed_limit_usage (user_id);

-- One row per UTC day for the whole project (TomTom's free plan allows about 2,500 calls a day for the app).
create table public.speed_limit_usage_total (
  day date primary key,
  calls int not null default 0 check (calls >= 0)
);

alter table public.speed_limit_usage enable row level security;
alter table public.speed_limit_usage_total enable row level security;
-- No policies: deny by default. Grants are the second wall.
revoke all on public.speed_limit_usage, public.speed_limit_usage_total from public, anon, authenticated;

-- Counts one call for `uid` today (UTC) if both quotas allow it, atomically under concurrent calls.
-- Returns 'ok', 'user' (8 calls per user per day used up) or 'project' (2,000 calls per day used up).
-- A refused call changes no counter.
create function public.take_speed_limit_quota(uid uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  today date := (now() at time zone 'utc')::date;
  n int;
begin
  if uid is null then
    raise exception 'user required' using errcode = '22023';
  end if;
  begin
    -- The conditional upsert locks the row, so two parallel calls cannot both take the last slot.
    insert into public.speed_limit_usage as u (day, user_id, calls) values (today, uid, 1)
    on conflict (day, user_id) do update set calls = u.calls + 1 where u.calls < 8
    returning u.calls into n;
    if n is null then
      return 'user';
    end if;
    insert into public.speed_limit_usage_total as t (day, calls) values (today, 1)
    on conflict (day) do update set calls = t.calls + 1 where t.calls < 2000
    returning t.calls into n;
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

-- Keeps 30 days of counters (enough to look at usage), deletes the rest.
create function public.prune_speed_limit_usage()
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.speed_limit_usage where day < (now() at time zone 'utc')::date - 30;
  delete from public.speed_limit_usage_total where day < (now() at time zone 'utc')::date - 30;
$$;

revoke execute on function public.take_speed_limit_quota(uuid) from public, anon, authenticated;
revoke execute on function public.prune_speed_limit_usage() from public, anon, authenticated;
grant execute on function public.take_speed_limit_quota(uuid) to service_role;

-- Prune daily where pg_cron exists (same pattern as the aggregation job in the core migration).
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron with schema pg_catalog;
    perform cron.schedule('prune-speed-limit-usage', '17 3 * * *', 'select public.prune_speed_limit_usage()');
  end if;
exception when others then
  raise notice 'pg_cron not scheduled: %', sqlerrm;
end;
$$;
