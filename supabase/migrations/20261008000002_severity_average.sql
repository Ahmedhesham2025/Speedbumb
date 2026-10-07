-- B1 follow-up: a spot's severity is averaged the way the app does it (core Model.kt, Bump.addSeverity). The first 10
-- hits count equally, then each new hit weighs 1/10: new = old + (x - old) / min(n, 10), n = the spot's hits so far, this
-- one included (the contributors' hits, the same total confidence uses). 0.8 * old + 0.2 * new kept most of the first
-- hit, so a shared band could differ from the phone's (hits 2, 6, 6: 3.44 mild instead of 4.67 moderate). Only future
-- hits change; stored severities stay as they are. Everything else is as in 20261008000001_bump_severity.sql.
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
  hits_so_far int;
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
        select coalesce(sum(c.hits), 0)::int into hits_so_far from public.spot_contributors c where c.spot_id = sid;
        update public.spots s
        set last_hit = greatest(s.last_hit, o.observed_hour),
            severity = case when sev is null then s.severity
                            else coalesce(s.severity + (sev - s.severity) / least(greatest(hits_so_far, 1), 10), sev) end,
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
-- CREATE OR REPLACE keeps the grants: still server only (no client may execute it).
