-- confirm_hits: a spot is confirmed only after 2 hits in total (one phone or several), on top of confirm_devices and the
-- pass ratio; the setting is read on every run (missing or invalid = 2); the aggregation stays server only.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

insert into auth.users (id, aud, role, email)
select ('c0000000-0000-0000-0000-00000000000' || i)::uuid, 'authenticated', 'authenticated', 'c' || i || '@test.local'
from generate_series(1, 2) i;
insert into public.devices (id, share_enabled)
select ('c0000000-0000-0000-0000-00000000000' || i)::uuid, true from generate_series(1, 2) i;
update public.app_settings set value = '1' where key = 'confirm_devices';  -- the hosted project's value

-- Test only (rolled back): observations from device d at a place, then the status of the spot there.
create function public.t_obs(d int, k text, lat double precision, lon double precision, n int default 1)
returns void language sql as $$
  insert into public.observations (device_id, client_obs_id, kind, geom, heading, peak, observed_hour)
  select ('c0000000-0000-0000-0000-00000000000' || d)::uuid, gen_random_uuid(), k,
         extensions.st_setsrid(extensions.st_makepoint(lon, lat), 4326)::extensions.geography, 90, 4.0,
         date_trunc('hour', now())
  from generate_series(1, n);
$$;
create function public.t_status(lat double precision, lon double precision) returns text language sql as $$
  select s.status from public.spots s
  where extensions.st_dwithin(s.geom, extensions.st_setsrid(extensions.st_makepoint(lon, lat), 4326)::extensions.geography, 15);
$$;

select is((select value from public.app_settings where key = 'confirm_hits'), '2', 'confirm_hits ships as 2');

do $$ begin
  perform public.t_obs(1, 'jolt', 23.1, 28.1);         -- one hit
  perform public.t_obs(1, 'jolt', 23.2, 28.2, 2);      -- two hits, one phone
  perform public.t_obs(1, 'jolt', 23.3, 28.3);         -- two hits, two phones
  perform public.t_obs(2, 'known_hit', 23.3, 28.3);
  perform public.t_obs(1, 'jolt', 23.4, 28.4, 2);      -- two hits, three clears
  perform public.t_obs(2, 'pass_clear', 23.4, 28.4, 3);
  perform public.aggregate_observations();
end $$;
select is(public.t_status(23.1, 28.1), 'candidate', 'one hit leaves a candidate, even with confirm_devices = 1');
select is(public.t_status(23.2, 28.2), 'confirmed', 'two hits from one phone confirm a spot');
select is(public.t_status(23.3, 28.3), 'confirmed', 'two hits from two phones confirm a spot');
select is(public.t_status(23.4, 28.4), 'candidate', 'two hits but three clears: the pass ratio still applies');

-- One more hit on the single-hit spot confirms it at the next run.
do $$ begin perform public.t_obs(2, 'known_hit', 23.1, 28.1); perform public.aggregate_observations(); end $$;
select is(public.t_status(23.1, 28.1), 'confirmed', 'its second hit (another run, another phone) confirms it');

-- The setting is read on every run; missing or invalid means 2.
update public.app_settings set value = 'many' where key = 'confirm_hits';
do $$ begin perform public.t_obs(1, 'jolt', 23.5, 28.5); perform public.aggregate_observations(); end $$;
select is(public.t_status(23.5, 28.5), 'candidate', 'an invalid confirm_hits falls back to 2');
update public.app_settings set value = '1' where key = 'confirm_hits';
do $$ begin perform public.t_obs(1, 'jolt', 23.6, 28.6); perform public.aggregate_observations(); end $$;
select is(public.t_status(23.6, 28.6), 'confirmed', 'confirm_hits = 1 confirms after one hit (the setting is read)');
-- confirm_devices still applies on top.
update public.app_settings set value = '2' where key = 'confirm_hits';
update public.app_settings set value = '2' where key = 'confirm_devices';
do $$ begin perform public.t_obs(1, 'jolt', 23.7, 28.7, 3); perform public.aggregate_observations(); end $$;
select is(public.t_status(23.7, 28.7), 'candidate', 'three hits from one phone stay a candidate with confirm_devices = 2');

select is(array[has_function_privilege('anon', 'public.aggregate_observations()', 'execute'),
                has_function_privilege('authenticated', 'public.aggregate_observations()', 'execute')],
          array[false, false], 'the aggregation is still server only');

select * from finish();
rollback;
