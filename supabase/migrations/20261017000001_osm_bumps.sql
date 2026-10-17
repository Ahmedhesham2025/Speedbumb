-- OpenStreetMap speed bumps as a prior for the app: nodes and ways tagged traffic_calming=bump|hump|table|cushion|yes or
-- the legacy highway=speed_bump inside Egypt's bounding box, imported at most weekly by the owner's tool
-- (supabase/tools/osm/import_osm_bumps.ts) from the Overpass API. Kept apart from the crowd-sourced spots.
-- Licence: a derived database of OpenStreetMap data, © OpenStreetMap contributors, under the ODbL (README).
-- No personal data: only OSM element ids, positions and the tag value.

-- ---------------------------------------------------------------- table
create table public.osm_bumps (
  osm_id text primary key check (osm_id ~ '^[nw][0-9]{1,15}$'),  -- 'n<node id>' or 'w<way id>' (a way's centre)
  lat double precision not null check (lat between 21.5 and 32.0),
  lon double precision not null check (lon between 24.5 and 37.5),
  kind text not null check (kind in ('bump', 'hump', 'table', 'cushion', 'yes', 'speed_bump')),
  geom extensions.geography(Point, 4326) not null,
  imported_at timestamptz not null default now()
);
create index osm_bumps_geom_idx on public.osm_bumps using gist (geom);
alter table public.osm_bumps enable row level security;
-- No policies: clients read through osm_bumps_near only; the import writes through the service-role RPCs below.
revoke all on public.osm_bumps from public, anon, authenticated, service_role;

-- ---------------------------------------------------------------- import (owner's tool, service role)
-- Upserts up to 5000 elements [{"osm_id": "n123", "lat": n, "lon": n, "kind": "hump"}, ...] stamped with run_at (the
-- start of the import run). The same batch again changes nothing but imported_at. Any bad element rejects the whole
-- batch (22023). Returns how many elements it took.
create function public.osm_bumps_import(batch jsonb, run_at timestamptz)
returns int language plpgsql security definer set search_path = '' as $$
declare
  n int;
begin
  if batch is null or jsonb_typeof(batch) <> 'array' or jsonb_array_length(batch) > 5000 or run_at is null then
    raise exception 'batch must be a JSON array of at most 5000 elements, with run_at' using errcode = '22023';
  end if;
  begin
    insert into public.osm_bumps as b (osm_id, lat, lon, kind, geom, imported_at)
    select e ->> 'osm_id', (e ->> 'lat')::double precision, (e ->> 'lon')::double precision, e ->> 'kind',
           extensions.st_setsrid(extensions.st_makepoint((e ->> 'lon')::double precision,
                                                         (e ->> 'lat')::double precision), 4326)::extensions.geography,
           run_at
    from jsonb_array_elements(batch) e
    on conflict (osm_id) do update
      set lat = excluded.lat, lon = excluded.lon, kind = excluded.kind, geom = excluded.geom,
          imported_at = greatest(b.imported_at, excluded.imported_at);
    get diagnostics n = row_count;
  exception when data_exception or check_violation or not_null_violation or cardinality_violation then
    raise exception 'invalid OSM batch: %', sqlerrm using errcode = '22023';
  end;
  return n;
end;
$$;

-- After a complete run: deletes the bumps that run no longer saw (removed or retagged in OSM). Returns how many.
create function public.osm_bumps_prune(run_at timestamptz)
returns int language sql security definer set search_path = '' as $$
  with gone as (delete from public.osm_bumps b where b.imported_at < run_at returning 1)
  select count(*)::int from gone;
$$;

-- For the tool's weekly guard: {"count": n, "last_import": timestamptz | null}.
create function public.osm_bumps_status()
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('count', count(*), 'last_import', max(b.imported_at)) from public.osm_bumps b;
$$;

-- ---------------------------------------------------------------- read (signed-in users)
-- A type rather than RETURNS TABLE, so the fields can be called lat and lon like the arguments.
create type public.osm_bump as (osm_id text, lat double precision, lon double precision, kind text);

-- OSM bumps around a point, nearest first: radius capped at 10 km, at most 2000.
create function public.osm_bumps_near(lat double precision, lon double precision, radius_m int default 3000)
returns setof public.osm_bump language sql stable security definer set search_path = '' as $$
  select b.osm_id, b.lat, b.lon, b.kind
  from public.osm_bumps b
  where extensions.st_dwithin(
          b.geom,
          extensions.st_setsrid(extensions.st_makepoint(osm_bumps_near.lon, osm_bumps_near.lat), 4326)::extensions.geography,
          least(greatest(coalesce(osm_bumps_near.radius_m, 3000), 0), 10000)::double precision)
  order by extensions.st_distance(
             b.geom,
             extensions.st_setsrid(extensions.st_makepoint(osm_bumps_near.lon, osm_bumps_near.lat), 4326)::extensions.geography),
           b.osm_id
  limit 2000;
$$;

revoke execute on function public.osm_bumps_import(jsonb, timestamptz), public.osm_bumps_prune(timestamptz),
  public.osm_bumps_status(), public.osm_bumps_near(double precision, double precision, int) from public, anon, authenticated;
grant execute on function public.osm_bumps_import(jsonb, timestamptz), public.osm_bumps_prune(timestamptz),
  public.osm_bumps_status() to service_role;
grant execute on function public.osm_bumps_near(double precision, double precision, int) to authenticated;
