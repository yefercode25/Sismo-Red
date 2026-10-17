-- Share per-axis movement and orientation for the current status row of each station.
-- Existing rows remain valid and expose null values until a station publishes again.
alter table public.station_status add column if not exists acceleration_x_m_s2 real;
alter table public.station_status add column if not exists acceleration_y_m_s2 real;
alter table public.station_status add column if not exists acceleration_z_m_s2 real;
alter table public.station_status add column if not exists rotation_x_deg real;
alter table public.station_status add column if not exists rotation_y_deg real;
alter table public.station_status add column if not exists rotation_z_deg real;
alter table public.station_status add column if not exists gyro_x_rad_s real;
alter table public.station_status add column if not exists gyro_y_rad_s real;
alter table public.station_status add column if not exists gyro_z_rad_s real;
