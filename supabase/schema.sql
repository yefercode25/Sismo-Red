-- Execute once in Supabase SQL Editor. The Android app only needs the publishable/anon key.
create extension if not exists pgcrypto;

create table if not exists public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null,
  display_name text not null,
  role text not null check (role in ('station', 'receiver')),
  region text not null default 'Guavio',
  municipality text not null default '',
  created_at timestamptz not null default now()
);

create table if not exists public.stations (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null unique references public.profiles(user_id) on delete cascade,
  display_name text not null,
  region text not null,
  municipality text not null,
  latitude double precision,
  longitude double precision,
  location_updated_at timestamptz,
  created_at timestamptz not null default now()
);
alter table public.stations add column if not exists latitude double precision;
alter table public.stations add column if not exists longitude double precision;
alter table public.stations add column if not exists location_updated_at timestamptz;
alter table public.stations drop constraint if exists stations_location_pair_check;
alter table public.stations add constraint stations_location_pair_check
  check ((latitude is null) = (longitude is null)
    and (latitude is null or latitude between -90 and 90)
    and (longitude is null or longitude between -180 and 180));
create unique index if not exists stations_one_per_municipality_idx
  on public.stations (lower(region), lower(municipality));

create table if not exists public.station_status (
  station_id uuid primary key references public.stations(id) on delete cascade,
  owner_id uuid not null references public.profiles(user_id) on delete cascade,
  station_name text not null,
  region text not null,
  municipality text not null,
  amplitude real not null,
  anomaly boolean not null default false,
  acceleration_x_m_s2 real,
  acceleration_y_m_s2 real,
  acceleration_z_m_s2 real,
  rotation_x_deg real,
  rotation_y_deg real,
  rotation_z_deg real,
  gyro_x_rad_s real,
  gyro_y_rad_s real,
  gyro_z_rad_s real,
  updated_at timestamptz not null default now()
);
alter table public.station_status add column if not exists acceleration_x_m_s2 real;
alter table public.station_status add column if not exists acceleration_y_m_s2 real;
alter table public.station_status add column if not exists acceleration_z_m_s2 real;
alter table public.station_status add column if not exists rotation_x_deg real;
alter table public.station_status add column if not exists rotation_y_deg real;
alter table public.station_status add column if not exists rotation_z_deg real;
alter table public.station_status add column if not exists gyro_x_rad_s real;
alter table public.station_status add column if not exists gyro_y_rad_s real;
alter table public.station_status add column if not exists gyro_z_rad_s real;

create table if not exists public.anomaly_events (
  id bigint generated always as identity primary key,
  station_id uuid not null references public.stations(id) on delete cascade,
  amplitude real not null check (amplitude >= 0),
  created_at timestamptz not null default now()
);
create index if not exists anomaly_events_station_time_idx on public.anomaly_events(station_id, created_at desc);

create table if not exists public.alerts (
  id uuid primary key default gen_random_uuid(),
  region text not null,
  municipality text not null,
  detected_at timestamptz not null default now(),
  station_count integer not null check (station_count >= 2),
  station_ids uuid[] not null
);
create index if not exists alerts_location_time_idx on public.alerts(region, municipality, detected_at desc);

create or replace function public.create_profile_and_station()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  account_role text := coalesce(new.raw_user_meta_data ->> 'role', 'receiver');
  account_name text := coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), split_part(new.email, '@', 1));
  account_region text := coalesce(nullif(new.raw_user_meta_data ->> 'region', ''), 'Guavio');
  account_municipality text := coalesce(new.raw_user_meta_data ->> 'municipality', '');
begin
  if account_role not in ('station', 'receiver') then account_role := 'receiver'; end if;
  insert into public.profiles(user_id, email, display_name, role, region, municipality)
  values (new.id, coalesce(new.email, ''), account_name, account_role, account_region, account_municipality);
  if account_role = 'station' then
    insert into public.stations(owner_id, display_name, region, municipality)
    values (new.id, account_name, account_region, account_municipality);
  end if;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created_sismored on auth.users;
create trigger on_auth_user_created_sismored
  after insert on auth.users
  for each row execute function public.create_profile_and_station();

create or replace function public.detect_community_consensus()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  source_station public.stations%rowtype;
  corroborating_count integer;
  corroborating_stations uuid[];
begin
  select * into source_station from public.stations where id = new.station_id;
  perform pg_advisory_xact_lock(hashtextextended(lower(source_station.region), 0));
  select count(distinct event.station_id), array_agg(distinct event.station_id)
    into corroborating_count, corroborating_stations
  from public.anomaly_events event
  join public.stations station on station.id = event.station_id
  where lower(station.region) = lower(source_station.region)
    and event.created_at between new.created_at - interval '10 seconds' and new.created_at + interval '10 seconds';

  if corroborating_count >= 2 and not exists (
    select 1 from public.alerts alert
    where alert.region = source_station.region
      and alert.detected_at >= new.created_at - interval '10 seconds'
  ) then
    insert into public.alerts(region, municipality, detected_at, station_count, station_ids)
    values (source_station.region, source_station.municipality, new.created_at, corroborating_count, corroborating_stations);
  end if;
  return new;
end;
$$;

drop trigger if exists on_anomaly_consensus on public.anomaly_events;
create trigger on_anomaly_consensus
  after insert on public.anomaly_events
  for each row execute function public.detect_community_consensus();

alter table public.profiles enable row level security;
alter table public.stations enable row level security;
alter table public.station_status enable row level security;
alter table public.anomaly_events enable row level security;
alter table public.alerts enable row level security;

drop policy if exists "read own profile" on public.profiles;
create policy "read own profile" on public.profiles for select to authenticated using (user_id = auth.uid());

drop policy if exists "authenticated users can read stations" on public.stations;
create policy "authenticated users can read stations" on public.stations for select to authenticated using (true);
drop policy if exists "station owners update their location" on public.stations;
create policy "station owners update their location" on public.stations for update to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists "authenticated users can read station status" on public.station_status;
create policy "authenticated users can read station status" on public.station_status for select to authenticated using (true);
drop policy if exists "station owner writes station status" on public.station_status;
create policy "station owner writes station status" on public.station_status for all to authenticated
  using (owner_id = auth.uid() and exists (select 1 from public.stations s where s.id = station_id and s.owner_id = auth.uid()))
  with check (owner_id = auth.uid() and exists (select 1 from public.stations s where s.id = station_id and s.owner_id = auth.uid()));

drop policy if exists "station owner publishes anomalies" on public.anomaly_events;
create policy "station owner publishes anomalies" on public.anomaly_events for insert to authenticated
  with check (exists (select 1 from public.stations s where s.id = station_id and s.owner_id = auth.uid()));

drop policy if exists "authenticated users read alerts" on public.alerts;
create policy "authenticated users read alerts" on public.alerts for select to authenticated using (true);

grant select on public.profiles, public.stations, public.station_status, public.alerts to authenticated;
grant update (latitude, longitude, location_updated_at) on public.stations to authenticated;
grant select, insert, update on public.station_status to authenticated;
grant insert on public.anomaly_events to authenticated;
grant usage, select on sequence public.anomaly_events_id_seq to authenticated;

do $$
begin
  alter publication supabase_realtime add table public.alerts;
exception when duplicate_object then null;
end;
$$;
do $$
begin
  alter publication supabase_realtime add table public.station_status;
exception when duplicate_object then null;
end;
$$;
do $$
begin
  alter publication supabase_realtime add table public.stations;
exception when duplicate_object then null;
end;
$$;
