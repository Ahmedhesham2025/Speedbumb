-- A fifth phone placement, "seat" (the phone lies on a car seat; Arabic «على الكرسي»). Training samples and trip
-- summaries accept it. Only the two placement CHECKs widen: every row that was valid stays valid, and builds that
-- never send 'seat' see no change. Until this is applied to the hosted project, the app maps 'seat' to 'unknown' in
-- its training upload (TrainingJson.SERVER_ACCEPTS_SEAT = false), so no batch is ever refused for it.
alter table public.training_samples
  drop constraint training_samples_placement_check,
  add constraint training_samples_placement_check
    check (placement in ('mounted', 'cupholder', 'pocket', 'seat', 'unknown'));
alter table public.training_trips
  drop constraint training_trips_placement_check,
  add constraint training_trips_placement_check
    check (placement in ('mounted', 'cupholder', 'pocket', 'seat', 'unknown'));
