-- Local/CI sample data only (never applied to the hosted project).
-- Coordinates are in empty Western Desert around 25.0 N, 30.0 E: no real roads, no real people.

insert into public.fleets (id, name) overriding system value values
  (1, 'Sample Fleet North'),
  (2, 'Sample Fleet South');

insert into public.vehicles (id, fleet_id, label) overriding system value values
  (1, 1, 'Van 1'),
  (2, 1, 'Van 2'),
  (3, 2, 'Truck 1');

insert into public.drivers (fleet_id, display_name, vehicle_id) values
  (1, 'Driver A', 1),
  (1, 'Driver B', 2),
  (2, 'Driver C', 3);

insert into public.spots (geom, heading, kind, side, severity, n_devices, n_hits, n_clear, status, last_hit)
values
  ('SRID=4326;POINT(30.0000 25.0000)', 90, 'bump', 'both', 0.6, 4, 9, 1, 'confirmed', now()),
  ('SRID=4326;POINT(30.0050 25.0010)', 90, 'pothole', 'right', 0.8, 3, 5, 2, 'confirmed', now()),
  ('SRID=4326;POINT(30.0100 25.0020)', 270, 'bump', 'both', 0.4, 3, 4, 0, 'confirmed', now()),
  ('SRID=4326;POINT(30.0150 25.0030)', 0, 'bump', 'left', 0.3, 1, 1, 0, 'candidate', now());

-- Keep the identity sequences ahead of the explicit ids above.
select setval(pg_get_serial_sequence('public.fleets', 'id'), 100);
select setval(pg_get_serial_sequence('public.vehicles', 'id'), 100);
