-- Private reports uploaded by the Phone Analyzer Android app.
-- Device authentication reuses the existing Termux bridge device token hash.
create table if not exists public.phone_analyzer_reports (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.termux_bridge_devices(id) on delete cascade,
  client_report_id uuid not null,
  created_at timestamptz not null default now(),
  report jsonb not null check (jsonb_typeof(report) = 'object'),
  summary jsonb not null default '{}'::jsonb check (jsonb_typeof(summary) = 'object'),
  report_size_bytes integer not null check (report_size_bytes between 0 and 5242880),
  unique (device_id, client_report_id)
);

create index if not exists phone_analyzer_reports_device_created_idx
  on public.phone_analyzer_reports (device_id, created_at desc);

alter table public.phone_analyzer_reports enable row level security;
revoke all on public.phone_analyzer_reports from anon, authenticated;
grant all on public.phone_analyzer_reports to service_role;
