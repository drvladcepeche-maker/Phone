import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: cors });
}

async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
}

function safeNumber(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

function makeSummary(report: Record<string, unknown>) {
  const apps = Array.isArray(report.apps) ? report.apps as Record<string, unknown>[] : [];
  const categories: Record<string, number> = {};
  for (const app of apps) {
    const category = typeof app.category === "string" ? app.category.slice(0, 80) : "Без категории";
    categories[category] = (categories[category] ?? 0) + 1;
  }
  const top = (field: "storage" | "traffic" | "foreground", limit = 15) => apps.map(app => {
    const storage = safeNumber(app.appBytes) + safeNumber(app.dataBytes) + safeNumber(app.cacheBytes);
    const value = field === "storage" ? storage :
      field === "traffic" ? safeNumber(app.trafficBytes30d) :
      safeNumber(app.foregroundMs30d);
    return {
      name: typeof app.name === "string" ? app.name.slice(0, 120) : "",
      package: typeof app.package === "string" ? app.package.slice(0, 200) : "",
      value,
    };
  }).filter(row => row.value > 0).sort((a, b) => b.value - a.value).slice(0, limit);

  const device = report.device && typeof report.device === "object"
    ? report.device as Record<string, unknown> : {};
  return {
    deviceModel: typeof device.model === "string" ? device.model.slice(0, 120) : "",
    androidVersion: typeof device.android === "string" ? device.android.slice(0, 40) : "",
    appCount: apps.length,
    categoryCounts: categories,
    topStorage: top("storage"),
    topTraffic30d: top("traffic"),
    topForeground30d: top("foreground"),
  };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return json({ error: "Server configuration missing" }, 500);

  const bearer = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  if (!/^[a-f0-9]{64}$/.test(bearer)) return json({ error: "Device token must be 64 lowercase hex characters" }, 401);

  let body: Record<string, unknown>;
  try {
    const compressed = req.headers.get("content-encoding")?.toLowerCase() === "gzip";
    const source = req.body;
    if (!source) return json({ error: "Request body required" }, 400);
    const decoded = compressed ? source.pipeThrough(new DecompressionStream("gzip")) : source;
    const reader = decoded.getReader();
    const decoder = new TextDecoder();
    let raw = "";
    let total = 0;
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > 26_214_400) {
        await reader.cancel();
        return json({ error: "Decompressed request exceeds 25 MiB" }, 413);
      }
      raw += decoder.decode(value, { stream: true });
    }
    raw += decoder.decode();
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return json({ error: "JSON object required" }, 400);
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "Invalid, oversized, or unsupported compressed JSON body" }, 400);
  }

  const supabase = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const tokenHash = await sha256(bearer);
  const { data: device, error: deviceError } = await supabase
    .from("termux_bridge_devices")
    .select("id, label")
    .eq("token_hash", tokenHash)
    .eq("enabled", true)
    .is("revoked_at", null)
    .maybeSingle();

  if (deviceError) return json({ error: "Device lookup failed" }, 500);
  if (!device) return json({ error: "Device is not registered, disabled, or revoked" }, 401);

  const action = String(body.action ?? "");
  await supabase.from("termux_bridge_devices")
    .update({ last_seen_at: new Date().toISOString() })
    .eq("id", device.id);

  if (action === "ping") return json({ ok: true, device: device.label });
  if (action !== "upload_report") return json({ error: "Unsupported action" }, 400);

  const report = body.report;
  const reportId = String(body.client_report_id ?? "");
  if (!report || typeof report !== "object" || Array.isArray(report)) return json({ error: "report object required" }, 400);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(reportId)) {
    return json({ error: "Valid client_report_id required" }, 400);
  }

  const reportObject = report as Record<string, unknown>;
  if (!Array.isArray(reportObject.apps)) return json({ error: "report.apps array required" }, 400);
  if (reportObject.apps.length > 7000) return json({ error: "Report exceeds the 7000-app limit" }, 413);

  const reportText = JSON.stringify(reportObject);
  const size = new TextEncoder().encode(reportText).length;
  if (size > 26_214_400) return json({ error: "Report exceeds 25 MiB" }, 413);

  const summary = makeSummary(reportObject);
  const { data, error } = await supabase
    .from("phone_analyzer_reports")
    .upsert({
      device_id: device.id,
      client_report_id: reportId,
      report: reportObject,
      summary,
      report_size_bytes: size,
    }, { onConflict: "device_id,client_report_id" })
    .select("id, created_at")
    .single();

  if (error || !data) return json({ error: "Could not store report" }, 500);
  return json({ ok: true, report_id: data.id, created_at: data.created_at, summary });
});
