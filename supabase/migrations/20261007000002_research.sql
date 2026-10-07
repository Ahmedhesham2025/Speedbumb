-- Research recordings: full-sensor gzip CSV files (~15-20 MB per hour, a 10-minute segment about 5 MB) from users who
-- said Yes on the first-start research consent screen, uploaded over Wi-Fi to a private Storage bucket. Phones reserve
-- room first, so the free 1 GB of Storage never overflows: at the cap uploads pause until the owner downloads and clears
-- the files (supabase/tools/research). Nothing here deletes a file; forget_me files go at the owner's next run (README).

-- ---------------------------------------------------------------- consent (only set_research_consent() changes it)
alter table public.devices
  add column research_consent boolean not null default false,
  add column research_consent_version int check (research_consent_version between 0 and 1000),  -- last version agreed to
  add column research_consent_at timestamptz;                                                     -- last change

-- ---------------------------------------------------------------- ledger and erasure queue (service role only)
create table public.research_uploads (
  name text primary key,                                       -- '<auth uid>/rr_<install>_<utc>_<segment>.csv.gz'
  device_id uuid references public.devices (id) on delete set null,  -- null: the user ran forget_me
  bytes bigint not null check (bytes between 1 and 10485760),  -- exact file size, at most 10 MB
  created_at timestamptz not null default now(),
  cleared_at timestamptz                                       -- downloaded + deleted by the owner: its room is free
);
create index research_uploads_open_idx on public.research_uploads (created_at) where cleared_at is null;
create index research_uploads_device_idx on public.research_uploads (device_id, created_at);
-- Users who ran forget_me after opting in: the owner's tool erases their folder unread on its next run, then the row.
create table public.research_forgotten (uid uuid primary key, at timestamptz not null default now());
alter table public.research_uploads enable row level security;
alter table public.research_forgotten enable row level security;
-- No policies: no client access at all. The owner's tool reads both and deletes the rows of erased users.
revoke all on public.research_uploads, public.research_forgotten from public, anon, authenticated, service_role;
grant select, delete on public.research_uploads, public.research_forgotten to service_role;
-- Caps in MB (1,048,576 bytes): un-cleared reservations in total, and per device in any 24 hours. A missing or
-- non-numeric value falls back to the default; research_cap_mb = 0 pauses all uploads.
insert into public.app_settings (key, value) values ('research_cap_mb', '900'), ('research_device_daily_mb', '300')
on conflict (key) do nothing;

-- ---------------------------------------------------------------- client RPCs
-- Research on (with the version of the consent text agreed to) or off: uploads stop at once, that version is kept.
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
  set research_consent = enabled, research_consent_at = now(),
      research_consent_version = case when enabled then version else d.research_consent_version end
  where d.id = auth.uid();
  return enabled;
end;
$$;
-- Reserves room for one file before the phone uploads it to research/<name>: 'ok' (upload now), 'full' (the cap would
-- be passed), 'device_daily' (research_device_daily_mb within 24 h) or 'no_consent'; a refusal stores nothing. 22023
-- (drop the file): not '<own auth uid>/rr_<8 hex>_<yyyymmddThhmmss>_<segment>.csv.gz' or not 1 byte..10 MB.
create function public.research_reserve(name text, bytes bigint)
returns text language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  holder uuid;
begin
  if uid is null or not exists (select 1 from public.devices d where d.id = uid and d.research_consent) then
    return 'no_consent';
  end if;
  if research_reserve.name is null or research_reserve.bytes is null
     or research_reserve.name !~ '^[0-9a-f-]{36}/rr_[0-9a-f]{8}_[0-9]{8}T[0-9]{6}_[0-9]{1,4}\.csv\.gz$'
     or split_part(research_reserve.name, '/', 1) <> uid::text
     or research_reserve.bytes not between 1 and 10485760 then
    raise exception 'invalid research file name or size' using errcode = '22023';
  end if;
  -- One reservation at a time, so two phones can't both take the last free megabytes.
  perform pg_advisory_xact_lock(hashtextextended('public.research_reserve', 0));
  select r.device_id into holder from public.research_uploads r where r.name = research_reserve.name;
  if found then  -- a repeat: same answer, nothing charged twice
    if holder is distinct from uid then
      raise exception 'research file name already used' using errcode = '22023';
    end if;
    return 'ok';
  end if;
  -- The cap counts the un-cleared reservations or, if more, what the bucket really holds.
  if greatest((select coalesce(sum(r.bytes), 0) from public.research_uploads r where r.cleared_at is null),
              (select coalesce(sum((o.metadata ->> 'size')::numeric), 0) from storage.objects o
               where o.bucket_id = 'research' and jsonb_typeof(o.metadata -> 'size') = 'number'))
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
  insert into public.research_uploads (name, device_id, bytes) values (research_reserve.name, uid, research_reserve.bytes);
  return 'ok';
end;
$$;
-- For the Storage policy: the caller's own live reservation, consent on, and a request size (Content-Length, which
-- Storage puts into metadata.contentLength for the check) of 1 byte up to the reserved bytes. No size = refused.
create function public.research_upload_allowed(object_name text, meta jsonb)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.research_uploads r join public.devices d on d.id = r.device_id
    where r.name = object_name and r.device_id = (select auth.uid()) and r.cleared_at is null and d.research_consent
      and case when jsonb_typeof(meta -> 'contentLength') = 'number' then (meta ->> 'contentLength')::numeric end
          between 1 and r.bytes);
$$;
-- forget_me also queues the caller's research files for erasure at the owner's next run of the tool.
create or replace function public.forget_me()
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  insert into public.research_forgotten (uid)
  select d.id from public.devices d where d.id = auth.uid() and d.research_consent_version is not null
  on conflict (uid) do update set at = now();
  delete from public.training_samples s
  where s.subject = (select d.training_subject from public.devices d where d.id = auth.uid());
  delete from public.training_trips t
  where t.subject = (select d.training_subject from public.devices d where d.id = auth.uid());
  delete from public.devices d where d.id = auth.uid();
end;
$$;
-- Owner only (service role): the tool calls this once files are downloaded and deleted from Storage; frees their room.
create function public.research_mark_cleared(names text[])
returns int language sql security definer set search_path = '' as $$
  with done as (
    update public.research_uploads r set cleared_at = now() where r.name = any (names) and r.cleared_at is null
    returning 1)
  select count(*)::int from done;
$$;

-- ---------------------------------------------------------------- Storage: private bucket (settings enforced) + policy
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('research', 'research', false, 10485760, array['application/gzip', 'application/octet-stream'])
on conflict (id) do update
  set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;
-- Only a plain POST upload (known Content-Length; no signed upload URL, TUS, S3 or copy) into the caller's own folder
-- with a live reservation. No select, update or delete policy: phones can't list, read, overwrite or delete anything.
create policy research_insert_reserved on storage.objects for insert to authenticated
  with check (bucket_id = 'research'
              and storage.allow_only_operation('object.upload')
              and (storage.foldername(name))[1] = (select auth.uid()::text)
              and public.research_upload_allowed(name, metadata));
revoke execute on function public.set_research_consent(boolean, int), public.research_reserve(text, bigint),
  public.research_upload_allowed(text, jsonb), public.research_mark_cleared(text[]) from public, anon;
revoke execute on function public.research_mark_cleared(text[]) from authenticated;
grant execute on function public.set_research_consent(boolean, int), public.research_reserve(text, bigint),
  public.research_upload_allowed(text, jsonb) to authenticated;
grant execute on function public.research_mark_cleared(text[]) to service_role;
