-- Research recordings: consent, reserve-then-upload, pause when full, owner clearing, Storage policy, forget_me queue.
begin;
create extension if not exists pgtap with schema extensions;
select plan(54);

insert into auth.users (id, aud, role, email) values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'a@test.local'),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'b@test.local'),
  ('33333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', 'c@test.local');
insert into public.devices (id) values ('11111111-1111-1111-1111-111111111111'), ('22222222-2222-2222-2222-222222222222');

-- Helpers (test only, rolled back): file n of user a/b/c, and an upload as the calling role the way Storage checks the
-- policy: it sets storage.operation, then inserts the row as the user with the request's Content-Length in metadata.
create function public.t_rr(who text, n int) returns text language sql as $$
  select translate(rpad('', 32, who), 'abc', '123')::uuid || '/rr_0a1b2c3d_20261007T201500_' || n || '.csv.gz';
$$;
create function public.t_put(obj text, len bigint default 1000, op text default 'storage.object.upload') returns void
language sql as $$
  select set_config('storage.operation', op, true);
  insert into storage.objects (bucket_id, name, owner_id, metadata)
  values ('research', obj, auth.uid()::text, jsonb_build_object('mimetype', 'application/gzip', 'contentLength', len));
$$;
grant execute on function public.t_rr(text, int) to authenticated, service_role;
grant execute on function public.t_put(text, bigint, text) to authenticated;

-- ---------------------------------------------------------------- shape
select has_column('public', 'devices', 'research_consent_version', 'devices hold the research consent version');
select ok((select bool_and(relrowsecurity) from pg_class where relname in ('research_uploads', 'research_forgotten')),
          'RLS is on for the research ledger and the erasure queue');
select is((select array[b.public::text, b.file_size_limit::text, array_to_string(b.allowed_mime_types, ' ')]
           from storage.buckets b where b.id = 'research'),
          array['false', '10485760', 'application/gzip application/octet-stream'], 'the research bucket: private, 10 MB, gzip');
select is((select array_agg(cmd || ' ' || array_to_string(roles, ' ')) from pg_policies where schemaname = 'storage'
           and tablename = 'objects' and coalesce(qual, '') || coalesce(with_check, '') like '%research%'),
          array['INSERT authenticated'], 'the only research policy lets signed-in users insert: no read, update or delete');
select is(array(select value from public.app_settings where key in ('research_cap_mb', 'research_device_daily_mb') order by key),
          array['900', '300'], 'the caps ship as 900 MB in total and 300 MB per device per day');

-- ---------------------------------------------------------------- consent and reservations (device A)
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select is(public.research_reserve(public.t_rr('a', 1), 1000), 'no_consent', 'nothing is reserved without research consent');
select throws_ok($$ update public.devices set research_consent = true $$, '42501', null, 'consent can''t be written directly');
select throws_ok($$ select public.set_research_consent(true, null) $$, '22023', null, 'opting in needs a consent version');
select is(public.set_research_consent(true, 1), true, 'a device can opt in to research recordings');
select is(public.research_reserve(public.t_rr('a', 1), 1000), 'ok', 'with consent a file is reserved');
select is(public.research_reserve(public.t_rr('a', 1), 1000), 'ok', 'reserving the same file again is ok');
select is(public.research_reserve(public.t_rr('a', 2), 1000), 'ok', 'a second file is reserved');
select throws_ok($$ select public.research_reserve(public.t_rr('b', 1), 1000) $$, '22023', null, 'a foreign folder is rejected');
select throws_ok($$ select public.research_reserve(public.t_rr('a', 1) || '.sh', 1000) $$, '22023', null,
                 'a name outside the rr_<install>_<utc>_<segment>.csv.gz pattern is rejected');
select throws_ok($$ select public.research_reserve(public.t_rr('a', 3), 10485761) $$, '22023', null, 'over 10 MB is rejected');
select throws_ok($$ select * from public.research_uploads $$, '42501', null, 'a device cannot read the ledger');
select throws_ok($$ insert into public.research_uploads (name, bytes) values ('x', 1) $$, '42501', null,
                 'a device cannot write the ledger');
select throws_ok($$ select * from public.research_forgotten $$, '42501', null, 'a device cannot read the erasure queue');
select throws_ok($$ select public.research_mark_cleared(array['x']) $$, '42501', null, 'a device cannot free the quota');
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select is(public.set_research_consent(true, 1), true, 'device B opts in');
select is(public.research_reserve(public.t_rr('b', 1), 1000), 'ok', 'device B reserves a file');

-- ---------------------------------------------------------------- Storage policy (device A)
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select lives_ok($$ select public.t_put(public.t_rr('a', 1)) $$, 'a reserved file can be uploaded into the own folder');
select throws_ok($$ select public.t_put(public.t_rr('a', 3)) $$, '42501', null, 'a file without a reservation is refused');
select throws_ok($$ select public.t_put(public.t_rr('b', 1)) $$, '42501', null,
                 'a file in another user''s folder is refused, even with that user''s reservation');
select throws_ok($$ select public.t_put(public.t_rr('a', 2), 1001) $$, '42501', null, 'bigger than reserved is refused');
select throws_ok($$ select public.t_put(public.t_rr('a', 2), null) $$, '42501', null,
                 'an upload without a Content-Length (chunked) is refused');
select throws_ok($$ select public.t_put(public.t_rr('a', 2), 1000, 'storage.object.sign_upload_url') $$, '42501', null,
                 'a signed upload URL cannot be created');
select throws_ok($$ select public.t_put(public.t_rr('a', 2), 1000, 'storage.s3.upload.create_multipart') $$, '42501', null,
                 'an S3 multipart upload is refused');
select is((select count(*) from storage.objects where bucket_id = 'research'), 0::bigint, 'a device cannot list research files');
-- Rename and delete attempts as the device (the Storage API sets allow_delete_query before deleting, so only RLS
-- decides). Errors don't matter; the file must be unchanged afterwards.
do $$ begin
  begin update storage.objects set name = name || '.x' where bucket_id = 'research'; exception when others then null; end;
  perform set_config('storage.allow_delete_query', 'true', true);
  begin delete from storage.objects where bucket_id = 'research'; exception when others then null; end;
end $$;
reset role;
select is((select array_agg(name) from storage.objects where bucket_id = 'research'), array[public.t_rr('a', 1)],
          'the device could neither rename nor delete its uploaded file');

-- ---------------------------------------------------------------- full: uploads pause, nothing is deleted
update public.app_settings set value = '1' where key = 'research_cap_mb';  -- open: a1 a2 b1 = 3,000 bytes of 1 MB
update storage.objects set metadata = metadata || '{"size": 1048576}' where name = public.t_rr('a', 1);
set local role authenticated;
select is(public.research_reserve(public.t_rr('a', 4), 1), 'full', 'what the bucket really holds counts when it is more');
reset role;
update storage.objects set metadata = metadata - 'size' where name = public.t_rr('a', 1);
set local role authenticated;
select is(public.research_reserve(public.t_rr('a', 4), 1045576), 'ok', 'a file that exactly fills the cap is reserved');
select is(public.research_reserve(public.t_rr('a', 5), 1), 'full', 'one byte more is refused: the bucket is full');
select is(public.research_reserve(public.t_rr('a', 2), 1000), 'ok', 'a file already reserved still goes up when full');
reset role;
select is((select array[count(*), sum(bytes)::bigint] from public.research_uploads), array[4, 1048576]::bigint[],
          'a refusal stores nothing and a repeat charges nothing twice');
-- Per device in 24 h: 1 MB. A has reserved 1,047,576 bytes today; B 1,000.
update public.app_settings set value = '900' where key = 'research_cap_mb';
update public.app_settings set value = '1' where key = 'research_device_daily_mb';
set local role authenticated;
select is(public.research_reserve(public.t_rr('a', 5), 1001), 'device_daily', 'a device over its daily cap is refused');
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select is(public.research_reserve(public.t_rr('b', 2), 1000), 'ok', 'other devices are not affected');
reset role;

-- ---------------------------------------------------------------- the owner clears (service role)
set local role service_role;
select is((select count(*) from public.research_uploads where cleared_at is null), 5::bigint, 'the owner can read the ledger');
select is(public.research_mark_cleared(array[public.t_rr('a', 1), public.t_rr('a', 4), 'no/such-file']), 2,
          'clearing marks the downloaded files and ignores unknown names');
reset role;
update public.app_settings set value = '1' where key = 'research_cap_mb';
update public.app_settings set value = '300' where key = 'research_device_daily_mb';
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select is(public.research_reserve(public.t_rr('a', 5), 1045576), 'ok', 'cleared files free their room under the cap');
select throws_ok($$ select public.t_put(public.t_rr('a', 4)) $$, '42501', null, 'a cleared file cannot be uploaded again');

-- ---------------------------------------------------------------- withdrawal keeps files; forget_me queues them
select is(public.set_research_consent(false, null), false, 'device A turns research off');
select is((select array[research_consent::text, research_consent_version::text] from public.devices where id = auth.uid()),
          array['false', '1'], 'turning off keeps the last consent version agreed to');
select is(public.research_reserve(public.t_rr('a', 6), 1), 'no_consent', 'after withdrawal nothing is reserved');
select throws_ok($$ select public.t_put(public.t_rr('a', 2)) $$, '42501', null, 'after withdrawal nothing is uploaded');
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select lives_ok($$ select public.forget_me() $$, 'device B asks to be forgotten');
select is(public.research_reserve(public.t_rr('b', 3), 1), 'no_consent', 'a forgotten device has no research consent');
do $$ begin perform public.register_device('1.8.0', 34, 3, false); end $$;
select is((select coalesce(research_consent_version::text, 'none') from public.devices where id = auth.uid()), 'none',
          'registering again starts without research consent');
reset role;
select is((select count(*) from public.research_uploads where name like '1111%'), 4::bigint, 'withdrawal deletes nothing');
select is(array[(select count(*) from public.research_uploads
                 where name like '2222%' and device_id is null and cleared_at is null),
                (select count(*) from public.research_forgotten where uid = '22222222-2222-2222-2222-222222222222'),
                (select count(*) from public.research_forgotten)], array[2, 1, 1]::bigint[],
          'forget_me unlinks B''s files and queues them for the owner''s next run; withdrawal queues nothing');
select is((select count(*) from storage.objects where bucket_id = 'research'), 1::bigint, 'uploaded files stay in the bucket');

-- ---------------------------------------------------------------- anon and unregistered
set local role anon;
select throws_ok($$ select public.research_reserve('x', 1) $$, '42501', null, 'anon cannot reserve');
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub":"33333333-3333-3333-3333-333333333333","role":"authenticated"}';
select throws_ok($$ select public.set_research_consent(true, 1) $$, '42501', null, 'an unregistered device cannot opt in');
select is(public.research_reserve(public.t_rr('c', 1), 1), 'no_consent', 'an unregistered device cannot reserve');
reset role;

select * from finish();
rollback;
