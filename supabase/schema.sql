-- BlazeMuzix Cloud — run this in the Supabase SQL editor (or supabase db push).
-- The Android app uses the public anon key + user JWTs only. Never put the
-- service-role key in the APK.

create extension if not exists "pgcrypto";

create table if not exists public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    username text unique,
    email text,
    avatar text,
    bio text,
    public_profile boolean not null default true,
    created_at timestamptz not null default now(),
    constraint username_len check (username is null or (char_length(username) between 3 and 32))
);

create table if not exists public.tracks (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles (id) on delete cascade,
    title text not null,
    artist text,
    description text,
    album text,
    genre text,
    year int,
    audio_url text not null,
    cover_url text,
    duration_ms bigint not null default 0,
    visibility text not null default 'public' check (visibility in ('public', 'private')),
    play_count bigint not null default 0,
    like_count bigint not null default 0,
    created_at timestamptz not null default now()
);

create table if not exists public.playlists (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles (id) on delete cascade,
    name text not null,
    description text,
    cover_url text,
    public boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists public.playlist_tracks (
    playlist_id uuid not null references public.playlists (id) on delete cascade,
    track_id uuid not null references public.tracks (id) on delete cascade,
    position int not null default 0,
    primary key (playlist_id, track_id)
);

create table if not exists public.likes (
    user_id uuid not null references public.profiles (id) on delete cascade,
    track_id uuid not null references public.tracks (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, track_id)
);

create table if not exists public.comments (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles (id) on delete cascade,
    track_id uuid not null references public.tracks (id) on delete cascade,
    body text not null check (char_length(body) between 1 and 2000),
    created_at timestamptz not null default now()
);

create table if not exists public.follows (
    follower_id uuid not null references public.profiles (id) on delete cascade,
    following_id uuid not null references public.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_id, following_id),
    constraint no_self_follow check (follower_id <> following_id)
);

create table if not exists public.reports (
    id uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references public.profiles (id) on delete cascade,
    target_type text not null,
    target_id text not null,
    reason text not null,
    created_at timestamptz not null default now()
);

create table if not exists public.playlist_follows (
    user_id uuid not null references public.profiles (id) on delete cascade,
    playlist_id uuid not null references public.playlists (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, playlist_id)
);

create table if not exists public.user_shorts (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles (id) on delete cascade,
    title text not null,
    description text,
    video_url text not null,
    thumb_url text,
    duration_ms bigint not null default 0,
    visibility text not null default 'public' check (visibility in ('public', 'private')),
    like_count bigint not null default 0,
    created_at timestamptz not null default now()
);

create index if not exists idx_tracks_created on public.tracks (created_at desc);
create index if not exists idx_tracks_owner on public.tracks (owner_id);
create index if not exists idx_tracks_likes on public.tracks (like_count desc);
create index if not exists idx_comments_track on public.comments (track_id, created_at);
create index if not exists idx_playlists_owner on public.playlists (owner_id);

alter table public.profiles enable row level security;
alter table public.tracks enable row level security;
alter table public.playlists enable row level security;
alter table public.playlist_tracks enable row level security;
alter table public.likes enable row level security;
alter table public.comments enable row level security;
        alter table public.follows enable row level security;
alter table public.reports enable row level security;
alter table public.playlist_follows enable row level security;
alter table public.user_shorts enable row level security;

-- Profiles: public rows readable; owner can update own row.
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles for select using (public_profile or auth.uid() = id);
drop policy if exists profiles_insert on public.profiles;
create policy profiles_insert on public.profiles for insert with check (auth.uid() = id);
drop policy if exists profiles_update on public.profiles;
create policy profiles_update on public.profiles for update using (auth.uid() = id) with check (auth.uid() = id);

-- Tracks: public visible to everyone; private only to owner; writes owner-only.
drop policy if exists tracks_select on public.tracks;
create policy tracks_select on public.tracks for select using (visibility = 'public' or owner_id = auth.uid());
drop policy if exists tracks_insert on public.tracks;
create policy tracks_insert on public.tracks for insert with check (owner_id = auth.uid());
drop policy if exists tracks_update on public.tracks;
create policy tracks_update on public.tracks for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists tracks_delete on public.tracks;
create policy tracks_delete on public.tracks for delete using (owner_id = auth.uid());

-- Playlists
drop policy if exists playlists_select on public.playlists;
create policy playlists_select on public.playlists for select using (public or owner_id = auth.uid());
drop policy if exists playlists_insert on public.playlists;
create policy playlists_insert on public.playlists for insert with check (owner_id = auth.uid());
drop policy if exists playlists_update on public.playlists;
create policy playlists_update on public.playlists for update using (owner_id = auth.uid());
drop policy if exists playlists_delete on public.playlists;
create policy playlists_delete on public.playlists for delete using (owner_id = auth.uid());

drop policy if exists playlist_tracks_select on public.playlist_tracks;
create policy playlist_tracks_select on public.playlist_tracks for select using (
    exists (select 1 from public.playlists p where p.id = playlist_id and (p.public or p.owner_id = auth.uid()))
);
drop policy if exists playlist_tracks_write on public.playlist_tracks;
create policy playlist_tracks_write on public.playlist_tracks for all using (
    exists (select 1 from public.playlists p where p.id = playlist_id and p.owner_id = auth.uid())
) with check (
    exists (select 1 from public.playlists p where p.id = playlist_id and p.owner_id = auth.uid())
);

-- Likes / comments / follows / reports
drop policy if exists likes_select on public.likes;
create policy likes_select on public.likes for select using (true);
drop policy if exists likes_write on public.likes;
create policy likes_write on public.likes for all using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists comments_select on public.comments;
create policy comments_select on public.comments for select using (
    exists (select 1 from public.tracks t where t.id = track_id and (t.visibility = 'public' or t.owner_id = auth.uid()))
);
drop policy if exists comments_insert on public.comments;
create policy comments_insert on public.comments for insert with check (user_id = auth.uid());
drop policy if exists comments_delete on public.comments;
create policy comments_delete on public.comments for delete using (user_id = auth.uid());

drop policy if exists follows_select on public.follows;
create policy follows_select on public.follows for select using (true);
drop policy if exists follows_write on public.follows;
create policy follows_write on public.follows for all using (follower_id = auth.uid()) with check (follower_id = auth.uid());

drop policy if exists reports_insert on public.reports;
create policy reports_insert on public.reports for insert with check (reporter_id = auth.uid());
drop policy if exists reports_select on public.reports;
create policy reports_select on public.reports for select using (reporter_id = auth.uid());

drop policy if exists playlist_follows_select on public.playlist_follows;
create policy playlist_follows_select on public.playlist_follows for select using (true);
drop policy if exists playlist_follows_write on public.playlist_follows;
create policy playlist_follows_write on public.playlist_follows for all using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists shorts_select on public.user_shorts;
create policy shorts_select on public.user_shorts for select using (visibility = 'public' or owner_id = auth.uid());
drop policy if exists shorts_insert on public.user_shorts;
create policy shorts_insert on public.user_shorts for insert with check (owner_id = auth.uid());
drop policy if exists shorts_update on public.user_shorts;
create policy shorts_update on public.user_shorts for update using (owner_id = auth.uid());
drop policy if exists shorts_delete on public.user_shorts;
create policy shorts_delete on public.user_shorts for delete using (owner_id = auth.uid());

-- Auto-create a profile when a user signs up.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, email, username, avatar)
    values (
        new.id,
        new.email,
        coalesce(new.raw_user_meta_data->>'username', split_part(new.email, '@', 1)),
        new.raw_user_meta_data->>'avatar_url'
    )
    on conflict (id) do update set email = excluded.email;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

create or replace function public.increment_play_count(track uuid)
returns void
language sql
security definer
set search_path = public
as $$
    update public.tracks set play_count = play_count + 1 where id = track and visibility = 'public';
$$;

create or replace function public.refresh_like_count()
returns trigger
language plpgsql
as $$
begin
    update public.tracks
    set like_count = (select count(*) from public.likes where track_id = coalesce(new.track_id, old.track_id))
    where id = coalesce(new.track_id, old.track_id);
    return null;
end;
$$;

drop trigger if exists likes_count on public.likes;
create trigger likes_count
    after insert or delete on public.likes
    for each row execute function public.refresh_like_count();

-- Account deletion from the signed-in client (no service-role key in the app).
create or replace function public.delete_own_account()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'not authenticated';
    end if;
    delete from auth.users where id = uid;
end;
$$;

grant execute on function public.increment_play_count(uuid) to anon, authenticated;
grant execute on function public.delete_own_account() to authenticated;

-- Storage buckets (also create in Dashboard → Storage if this is skipped).
insert into storage.buckets (id, name, public)
values ('covers', 'covers', true), ('avatars', 'avatars', true), ('audio', 'audio', true), ('videos', 'videos', true)
on conflict (id) do nothing;

drop policy if exists audio_read on storage.objects;
create policy audio_read on storage.objects for select using (bucket_id in ('audio', 'covers', 'avatars', 'videos'));

drop policy if exists audio_write on storage.objects;
create policy audio_write on storage.objects for insert to authenticated
    with check (
        bucket_id in ('audio', 'covers', 'avatars', 'videos')
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists audio_update on storage.objects;
create policy audio_update on storage.objects for update to authenticated
    using (
        bucket_id in ('audio', 'covers', 'avatars', 'videos')
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists audio_delete on storage.objects;
create policy audio_delete on storage.objects for delete to authenticated
    using (
        bucket_id in ('audio', 'covers', 'avatars', 'videos')
        and (storage.foldername(name))[1] = auth.uid()::text
    );
