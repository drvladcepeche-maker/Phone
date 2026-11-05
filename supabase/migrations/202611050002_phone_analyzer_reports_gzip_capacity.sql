-- Increase the allowed uncompressed report size; the Android client sends gzip.
alter table public.phone_analyzer_reports
  drop constraint if exists phone_analyzer_reports_report_size_bytes_check;

alter table public.phone_analyzer_reports
  add constraint phone_analyzer_reports_report_size_bytes_check
  check (report_size_bytes between 0 and 26214400);
