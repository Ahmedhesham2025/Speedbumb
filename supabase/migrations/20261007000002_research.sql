-- Research recordings: full-sensor gzip CSV files (~15-20 MB per hour, 10-minute segments) from phones whose user said
-- Yes on the first-start research consent screen, uploaded over Wi-Fi to a private Storage bucket. A phone first
-- reserves room in a ledger, so the free plan's 1 GB of Storage never overflows: at the cap, reservations are refused
-- and uploads pause until the owner downloads and clears the files (tools/research). Nothing here deletes a file on its
-- own, not even withdrawal or forget_me (owner decision; README "Research recordings").

-- ---------------------------------------------------------------- consent (server-side, versioned)
alter table public.devices
  add column research_consent_version int check (research_consent_version between 0 and 1000),  -- null = never / withdrawn
  add column research_consent_at timestamptz;                                                     -- last change
-- No column grant: only set_research_consent() changes these.

-- ---------------------------------------------------------------- ledger: one row per reserved file
create table public.research_uploads (
  name text primary key,                                       -- '<auth uid>/rr_<install>_<utc>_<segment>.csv.gz'
  device_id uuid references public.devices (id) on delete set null,
  bytes bigint not null check (bytes between 1 and 26214400),  -- size the phone declared, at most 25 MB
  created_at timestamptz not null default now(),
  cleared_at timestamptz                                       -- owner downloaded + deleted it: its room is free again
);
create index research_uploads_open_idx on public.research_uploads (created_at) where cleared_at is null;
create index research_uploads_device_idx on public.research_uploads (device_id, created_at);
alter table public.research_uploads enable row level security;
-- No policies: no client access. The owner's tool reads it (service role) and, for a deletion request, deletes the
-- cleared rows of that user.
revoke all on public.research_uploads from public, anon, authenticated, service_role;
grant select, delete on public.research_uploads to service_role;

-- Caps in MB (1,048,576 bytes): un-cleared reservations in total, and per device in any 24 hours. A missing or
-- non-numeric value falls back to the default; research_cap_mb = 0 pauses all uploads.
insert into public.app_settings (key, value) values ('research_cap_mb', '900'), ('research_device_daily_mb', '300')
on conflict (key) do nothing;

-- ---------------------------------------------------------------- client RPCs
-- Research recording on (with the version of the consent text agreed to) or off for the caller's device.
-- Off stops new reservations and uploads at once; files already uploaded stay until the owner clears them.
create function public.set_research_consent(enabled boolean, version int)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null or not exists (select 1 from public.devices d where d.id = auth.uid()) then
    raise exception 'device not registered' using errcode = '42501';
  end if;
  if enabled is null or (enabled and (version is null or version not between 0 and 1000)) then
    raise exception 'enabled and a consent version 0..1000 are required' using errcode = '22023';
  end if;
  update public.devices d
  set research_consent_version = case when enabled then version end, research_consent_at = now()
  where d.id = auth.uid();
  return enabled;
end;
$$;

-- Reserves room for one file before the phone uploads it to research/<name>. Returns 'ok' (upload now), 'full' (the
-- un-cleared total would pass research_cap_mb), 'device_daily' (this device would pass research_device_daily_mb within
-- 24 h) or 'no_consent'. A refusal stores nothing; the phone pauses and retries the next day. 22023 (drop the file):
-- the name is not '<own auth uid>/rr_<8 hex>_<yyyymmddThhmmss>_<segment>.csv.gz' or the size is not 1 byte..25 MB.
-- Reserving the same name again returns 'ok' and charges nothing twice.
create function public.research_reserve(name text, bytes bigint)
returns text language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  holder uuid;
begin
  if uid is null or not exists (
       select 1 from public.devices d where d.id = uid and d.research_consent_version is not null) then
    return 'no_consent';
  end if;
  if research_reserve.name is null or research_reserve.bytes is null
     or research_reserve.name !~ '^[0-9a-f-]{36}/rr_[0-9a-f]{8}_[0-9]{8}T[0-9]{6}_[0-9]{1,4}\.csv\.gz$'
     or split_part(research_reserve.name, '/', 1) <> uid::text
     or research_reserve.bytes not between 1 and 26214400 then
    raise exception 'invalid research file name or size' using errcode = '22023';
  end if;
  -- One reservation at a time, so two phones can't both take the last free megabytes.
  perform pg_advisory_xact_lock(hashtextextended('public.research_reserve', 0));
  select r.device_id into holder from public.research_uploads r where r.name = research_reserve.name;
  if found then
    if holder is distinct from uid then
      raise exception 'research file name already used' using errcode = '22023';
    end if;
    return 'ok';
  end if;
  if (select coalesce(sum(r.bytes), 0) from public.research_uploads r where r.cleared_at is null)
     + research_reserve.bytes > 1048576 * coalesce((select case when a.value ~ '^[0-9]{1,7}$' then a.value::bigint end
                                                    from public.app_settings a where a.key = 'research_cap_mb'), 900) then
    return 'full';
  end if;
  if (select coalesce(sum(r.bytes), 0) from public.research_uploads r
      where r.device_id = uid and r.created_at > now() - interval '24 hours')
     + research_reserve.bytes > 1048576 * coalesce((select case when a.value ~ '^[0-9]{1,7}$' then a.value::bigint end
                                                    from public.app_settings a where a.key = 'research_device_daily_mb'), 300) then
    return 'device_daily';
  end if;
  insert into public.research_uploads (name, device_id, bytes)
  values (research_reserve.name, uid, research_reserve.bytes);
  return 'ok';
end;
$$;

-- Used by the Storage policy below: may the caller upload this object now? It needs its own live (not cleared)
-- reservation, research consent still on and, when Storage reports the request size (metadata.contentLength at the
-- policy check), no more bytes than reserved.
create function public.research_upload_allowed(object_name text, meta jsonb)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.research_uploads r join public.devices d on d.id = r.device_id
    where r.name = object_name and r.device_id = (select auth.uid()) and r.cleared_at is null
      and d.research_consent_version is not null
      and (jsonb_typeof(meta -> 'contentLength') is distinct from 'number'
           or (meta ->> 'contentLength')::numeric <= r.bytes));
$$;

-- ---------------------------------------------------------------- owner only (service role)
-- Called by tools/research/download_and_clear.ts once files are downloaded and deleted from Storage: frees their room.
create function public.research_mark_cleared(names text[])
returns int language sql security definer set search_path = '' as $$
  with done as (
    update public.research_uploads r set cleared_at = now()
    where r.name = any (names) and r.cleared_at is null
    returning 1)
  select count(*)::int from done;
$$;

-- ---------------------------------------------------------------- Storage: private bucket + upload policy
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('research', 'research', false, 26214400, array['application/gzip', 'application/octet-stream'])
on conflict (id) do nothing;
-- Upload only into the caller's own folder with a live reservation. Deliberately no select, update or delete policy
-- for this bucket: phones can't list, download, overwrite or delete research files; only the owner (service role) can.
create policy research_insert_reserved on storage.objects for insert to authenticated
  with check (bucket_id = 'research'
              and (storage.foldername(name))[1] = (select auth.uid()::text)
              and public.research_upload_allowed(name, metadata));

revoke execute on function public.set_research_consent(boolean, int) from public, anon;
revoke execute on function public.research_reserve(text, bigint) from public, anon;
revoke execute on function public.research_upload_allowed(text, jsonb) from public, anon;
revoke execute on function public.research_mark_cleared(text[]) from public, anon, authenticated;
grant execute on function public.set_research_consent(boolean, int) to authenticated;
grant execute on function public.research_reserve(text, bigint) to authenticated;
grant execute on function public.research_upload_allowed(text, jsonb) to authenticated;
grant execute on function public.research_mark_cleared(text[]) to service_role;
