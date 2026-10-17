-- Add station coordinates without resetting existing SismoRed data.
alter table public.stations add column if not exists latitude double precision;
alter table public.stations add column if not exists longitude double precision;
alter table public.stations add column if not exists location_updated_at timestamptz;

alter table public.stations drop constraint if exists stations_location_pair_check;
alter table public.stations add constraint stations_location_pair_check
  check ((latitude is null) = (longitude is null)
    and (latitude is null or latitude between -90 and 90)
    and (longitude is null or longitude between -180 and 180));

drop policy if exists "station owners update their location" on public.stations;
create policy "station owners update their location" on public.stations for update to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

grant update (latitude, longitude, location_updated_at) on public.stations to authenticated;
