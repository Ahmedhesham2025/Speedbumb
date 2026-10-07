-- v2 bumps (Sprint 1 B1): no more potholes. Every spot is a bump with a severity band (mild < 3.5 <= moderate < 5.0
-- <= strong, from its averaged severity in m/s²) and a confidence: soft (a "maybe") or full. The old pothole spots become
-- legacy bumps that stay soft until a v2 phone feels them again. spots_near keeps its signature, so 1.7.x and E1 builds
-- keep working; v2 builds call spots_near_v2. Apply on the hosted project before any v2.0 build uploads (README).

-- ---------------------------------------------------------------- columns and settings
alter table public.spots
  add column severity_band text check (severity_band in ('mild', 'moderate', 'strong')),
  add column confidence text not null default 'soft' check (confidence in ('soft', 'full')),
  add column axle_hits int not null default 0 check (axle_hits >= 0),  -- hits with both axles felt (axle >= 0.6)
  add column legacy_pothole boolean not null default false;            -- an old pothole spot: soft until a v2 hit
-- Optional, sent by v2 phones; 1.7.x phones send none. kind_score / side_score are still stored, as diagnostics only.
alter table public.observations
  add column sev_index real check (sev_index between 0 and 100),  -- the hit's severity index (else its peak is used)
  add column axle real check (axle between 0 and 1),              -- axle score: >= 0.6 = both axles felt
  add column schema smallint not null default 1 check (schema between 1 and 100);  -- 2 = a v2 phone
-- Band edges (m/s²), refitted from test drives: change them with one UPDATE, then run public.backfill_bump_severity().
insert into public.app_settings (key, value) values ('severity_moderate_min', '3.5'), ('severity_strong_min', '5.0')
on conflict (key) do nothing;

-- ---------------------------------------------------------------- rules (server only)
-- mild < severity_moderate_min <= moderate < severity_strong_min <= strong; null without a severity.
-- A missing or non-numeric setting falls back to 3.5 / 5.0.
create function public.severity_band(sev real)
returns text language sql stable set search_path = '' as $$
  select case
    when sev is null then null
    when sev >= coalesce((select case when a.value ~ '^[0-9]{1,3}(\.[0-9]{1,3})?$' then a.value::real end
                          from public.app_settings a where a.key = 'severity_strong_min'), 5.0) then 'strong'
    when sev >= coalesce((select case when a.value ~ '^[0-9]{1,3}(\.[0-9]{1,3})?$' then a.value::real end
                          from public.app_settings a where a.key = 'severity_moderate_min'), 3.5) then 'moderate'
    else 'mild' end;
$$;

-- The engine's rule (core Model.kt, Bump.confidence): full after two hits in total, from one phone or several, or after
-- one hit with both axles felt on a strong bump. A legacy pothole stays soft.
create function public.spot_confidence(legacy boolean, hits int, axle_hits int, band text)
returns text language sql immutable set search_path = '' as $$
  select case when legacy then 'soft'
              when hits >= 2 or (axle_hits >= 1 and band = 'strong') then 'full'
              else 'soft' end;
$$;

-- Old pothole spots become legacy bumps, and every spot gets its band and its confidence from its total hits (the sum of
-- its contributors' hits). kind stays 'pothole' exactly while legacy_pothole is set (the aggregation keeps it so), so
-- running this again is safe, e.g. after changing the band edges. The migration runs it once. Returns the spots seen.
create function public.backfill_bump_severity()
returns int language sql set search_path = '' as $$
  with done as (
    update public.spots s
    set legacy_pothole = coalesce(s.kind = 'pothole', false),
        severity_band = public.severity_band(s.severity),
        confidence = public.spot_confidence(coalesce(s.kind = 'pothole', false),
                       (select coalesce(sum(c.hits), 0)::int from public.spot_contributors c where c.spot_id = s.id),
                       s.axle_hits, public.severity_band(s.severity))
    returning 1)
  select count(*)::int from done;
$$;

-- ---------------------------------------------------------------- submit_observations: old payloads + optional v2 fields
-- Same contract as before (README), plus per element, all optional: "sev_index": 0..100, "axle": 0..1 and "schema": an
-- integer 1..100 (default 1; v2 phones send 2). A bad value rejects the whole batch (22023), like any other field.
create or replace function public.submit_observations(batch jsonb)
returns uuid[]
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := auth.uid();
  n int;
  bad int;
  recent int;
begin
  if uid is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if not exists (select 1 from public.devices d where d.id = uid and d.share_enabled) then
    raise exception 'device not registered or sharing is off' using errcode = '42501';
  end if;
  if batch is null or jsonb_typeof(batch) <> 'array' then
    raise exception 'batch must be a JSON array' using errcode = '22023';
  end if;
  n := jsonb_array_length(batch);
  if n = 0 then
    return '{}'::uuid[];
  end if;
  if n > 500 then
    raise exception 'batch too large (% > 500)', n using errcode = '22023';
  end if;

  select count(*) into recent
  from public.observations o
  where o.device_id = uid and o.created_at > now() - interval '1 day';
  if recent + n > 2000 then
    raise exception 'daily upload limit reached' using errcode = '54000';
  end if;

  -- Any bad field (kind, box, uuid, number, timestamp, range) rejects the whole batch with 22023,
  -- so the phone can drop it instead of retrying forever.
  begin
    -- Egypt / MENA box only; times within the last 30 days.
    select count(*) into bad
    from jsonb_array_elements(batch) e
    where not coalesce(
      jsonb_typeof(e) = 'object'
      and e ->> 'client_obs_id' is not null
      and e ->> 'kind' in ('jolt', 'known_hit', 'pass_clear')
      and jsonb_typeof(e -> 'lat') = 'number'
      and jsonb_typeof(e -> 'lon') = 'number'
      and (e ->> 'lat')::double precision between 12 and 38
      and (e ->> 'lon')::double precision between 24 and 60
      and (e ->> 'observed_at')::timestamptz between now() - interval '30 days' and now() + interval '1 hour',
      false);
    if bad > 0 then
      raise exception '% invalid observation(s) in batch', bad using errcode = '22023';
    end if;

    insert into public.observations
      (device_id, client_obs_id, kind, geom, heading, speed_kmh, peak, kind_score, side_score, sev_index, axle, schema,
       observed_hour)
    select uid,
           (e ->> 'client_obs_id')::uuid,
           e ->> 'kind',
           extensions.st_setsrid(
             extensions.st_makepoint((e ->> 'lon')::double precision, (e ->> 'lat')::double precision), 4326
           )::extensions.geography,
           (e ->> 'heading')::smallint,
           (e ->> 'speed_kmh')::smallint,
           (e ->> 'peak')::real,
           (e ->> 'kind_score')::real,
           (e ->> 'side_score')::real,
           (e ->> 'sev_index')::real,
           (e ->> 'axle')::real,
           coalesce((e ->> 'schema')::smallint, 1),
           date_trunc('hour', (e ->> 'observed_at')::timestamptz)
    from jsonb_array_elements(batch) e
    on conflict (device_id, client_obs_id) do nothing;
  exception when data_exception or check_violation or not_null_violation then
    raise exception 'invalid observation batch: %', sqlerrm using errcode = '22023';
  end;

  return (select array_agg(distinct (e ->> 'client_obs_id')::uuid) from jsonb_array_elements(batch) e);
end;
$$;

-- ---------------------------------------------------------------- aggregation: bumps only
-- Matching, counters, confirm_devices, stale and cleanup as before. Since v2: a new spot is always a bump (no side);
-- severity is the running average of the hits' severity index (their peak when a phone sends none); band and
-- confidence follow; axle_hits counts hits with axle >= 0.6; a hit from a v2 phone (schema >= 2) or an axle hit turns a
-- legacy pothole into an ordinary bump.
create or replace function public.aggregate_observations()
returns int
language plpgsql
security definer
set search_path = ''
as $$
declare
  o record;
  sid bigint;
  is_hit boolean;
  sev real;
  renew boolean;
  touched bigint[] := '{}';
  done int := 0;
  need int;
begin
  for o in
    select ob.id, ob.device_id, ob.kind, ob.geom, ob.heading, ob.peak, ob.sev_index, ob.axle, ob.schema, ob.observed_hour
    from public.observations ob
    where not ob.processed
    order by ob.id
    for update skip locked
  loop
    is_hit := o.kind <> 'pass_clear';
    sev := coalesce(o.sev_index, o.peak);
    renew := o.schema >= 2 or coalesce(o.axle >= 0.6, false);
    select s.id into sid
    from public.spots s
    where s.status <> 'retired'
      and extensions.st_dwithin(s.geom, o.geom, 15)
      and (s.heading is null or o.heading is null
           or abs(((s.heading::int - o.heading::int) % 360 + 540) % 360 - 180) <= 45)
    order by extensions.st_distance(s.geom, o.geom)
    limit 1;

    if sid is null and is_hit then
      insert into public.spots (geom, heading, kind, severity, first_seen, last_hit)
      values (o.geom, o.heading, 'bump', sev, o.observed_hour, o.observed_hour)
      returning id into sid;
    end if;

    if sid is not null then
      insert into public.spot_contributors as c (spot_id, device_id, hits, clears)
      values (sid, o.device_id, case when is_hit then 1 else 0 end, case when is_hit then 0 else 1 end)
      on conflict (spot_id, device_id) do update
        set hits = c.hits + excluded.hits,
            clears = c.clears + excluded.clears;
      if is_hit then
        update public.spots s
        set last_hit = greatest(s.last_hit, o.observed_hour),
            severity = case when sev is null then s.severity else coalesce(0.8 * s.severity + 0.2 * sev, sev) end,
            axle_hits = s.axle_hits + case when o.axle >= 0.6 then 1 else 0 end,
            legacy_pothole = s.legacy_pothole and not renew,
            kind = case when s.legacy_pothole and renew then 'bump' else s.kind end,
            side = case when s.legacy_pothole and renew then null else s.side end
        where s.id = sid;
      end if;
      touched := touched || sid;
    end if;

    update public.observations set processed = true where id = o.id;
    done := done + 1;
  end loop;

  update public.spots s
  set n_devices = c.n_dev, n_hits = c.n_hits, n_clear = c.n_clear, updated_at = now(),
      severity_band = public.severity_band(s.severity),
      confidence = public.spot_confidence(s.legacy_pothole, c.n_hits, s.axle_hits, public.severity_band(s.severity))
  from (
    select sc.spot_id,
           count(*) filter (where sc.hits > 0)::int as n_dev,
           sum(sc.hits)::int as n_hits,
           sum(sc.clears)::int as n_clear
    from public.spot_contributors sc
    where sc.spot_id = any (touched)
    group by sc.spot_id
  ) c
  where s.id = c.spot_id;

  -- Confirm with app_settings.confirm_devices distinct devices (3 if the setting is missing or not a
  -- positive number) and at least half of the passes hit.
  select greatest(coalesce(
           (select case when a.value ~ '^[0-9]{1,4}$' then a.value::int end
            from public.app_settings a where a.key = 'confirm_devices'), 3), 1)
  into need;
  update public.spots s
  set status = case
      when s.n_hits::real / greatest(s.n_hits + s.n_clear, 1) >= 0.5
           and s.n_devices >= need
        then 'confirmed'
      when s.status = 'confirmed' then 'stale'
      else s.status end
  where s.id = any (touched) and s.status <> 'retired';

  delete from public.observations ob where ob.processed and ob.created_at < now() - interval '1 day';
  return done;
end;
$$;

-- ---------------------------------------------------------------- spots_near (v1, 1.7.x and E1 builds) and spots_near_v2
-- Same signature and columns as before. kind is 'pothole' only for a legacy pothole, else 'bump'; side as stored
-- (null for spots made since v2).
create or replace function public.spots_near(lat double precision, lon double precision, radius_m int default 3000)
returns table (
  id bigint,
  latitude double precision,
  longitude double precision,
  heading smallint,
  kind text,
  side text,
  severity real,
  n_devices int,
  last_hit timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select s.id,
         extensions.st_y(s.geom::extensions.geometry),
         extensions.st_x(s.geom::extensions.geometry),
         s.heading, case when s.legacy_pothole then 'pothole' else 'bump' end, s.side, s.severity, s.n_devices, s.last_hit
  from public.spots s
  where s.status = 'confirmed'
    and extensions.st_dwithin(
          s.geom,
          extensions.st_setsrid(extensions.st_makepoint(spots_near.lon, spots_near.lat), 4326)::extensions.geography,
          least(greatest(coalesce(spots_near.radius_m, 3000), 0), 10000)::double precision)
  order by s.id
  limit 5000;
$$;

-- One spot as spots_near_v2 returns it. A type rather than RETURNS TABLE, so its fields can be called lat and lon like
-- the function's arguments.
create type public.spot_v2 as (
  id bigint,
  lat double precision,
  lon double precision,
  heading smallint,
  severity real,       -- averaged severity, m/s²
  severity_band text,  -- mild | moderate | strong (null without a severity)
  confidence text,     -- soft | full
  n_devices int,       -- distinct phones that felt it
  n_hits int,          -- hits in total, all phones together
  legacy boolean       -- an old pothole spot no v2 phone has felt yet (always soft)
);

-- Confirmed spots around a point for v2 builds: the same filter as spots_near (radius capped at 10 km, at most 5000).
create function public.spots_near_v2(lat double precision, lon double precision, radius_m int default 3000)
returns setof public.spot_v2
language sql
stable
security definer
set search_path = ''
as $$
  select s.id,
         extensions.st_y(s.geom::extensions.geometry),
         extensions.st_x(s.geom::extensions.geometry),
         s.heading, s.severity, s.severity_band, s.confidence, s.n_devices, s.n_hits, s.legacy_pothole
  from public.spots s
  where s.status = 'confirmed'
    and extensions.st_dwithin(
          s.geom,
          extensions.st_setsrid(extensions.st_makepoint(spots_near_v2.lon, spots_near_v2.lat), 4326)::extensions.geography,
          least(greatest(coalesce(spots_near_v2.radius_m, 3000), 0), 10000)::double precision)
  order by s.id
  limit 5000;
$$;

-- ---------------------------------------------------------------- training samples (issue #114)
-- Since E1 the app's classification is the jolt's severity band. bump / pothole / unsure stay valid (samples queued by
-- older builds); hand labels may use the bands too.
alter table public.training_samples
  drop constraint training_samples_classification_check,
  add constraint training_samples_classification_check
    check (classification in ('mild', 'moderate', 'strong', 'bump', 'pothole', 'unsure')),
  drop constraint training_samples_label_check,
  add constraint training_samples_label_check
    check (label in ('mild', 'moderate', 'strong', 'bump', 'pothole', 'none', 'unsure'));

-- ---------------------------------------------------------------- grants and the backfill
-- submit_observations, aggregate_observations and spots_near keep their grants (CREATE OR REPLACE).
revoke execute on function public.severity_band(real), public.spot_confidence(boolean, int, int, text),
  public.backfill_bump_severity() from public, anon, authenticated;
revoke execute on function public.spots_near_v2(double precision, double precision, int) from public, anon;
grant execute on function public.spots_near_v2(double precision, double precision, int) to authenticated;

select public.backfill_bump_severity();
