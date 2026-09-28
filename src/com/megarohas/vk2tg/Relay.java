package com.megarohas.vk2tg;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dedup → persistent queue → Telegram, with retries. The queue and the
 * "already forwarded" set live in SharedPreferences, so nothing is lost or
 * sent twice when Android kills the process; all mutations happen on one
 * worker thread.
 */
final class Relay {
    static final String TAG = "vk2tg";

    static final String K_TOKEN = "token", K_CHAT = "chat", K_SHELL = "shell", K_QUEUE = "queue",
            K_SEEN = "seen", K_SENT = "sent", K_LAST_OK = "last_ok", K_ERROR = "error",
            K_ERROR_AT = "error_at", K_CONNECTED = "connected";

    private static final int MAX_QUEUE = 500, MAX_SEEN = 3000, BATCH_CHARS = 3500;
    private static final long SEEN_TTL = TimeUnit.DAYS.toMillis(3);

    /** One chat message, already formatted: bold header line + text. */
    static final class Msg {
        final String fp, header, text;

        Msg(String pkg, String chat, String sender, String text, long time, boolean group) {
            chat = cut(chat, 100);
            sender = cut(sender, 100);
            text = cut(text, 3000);
            if (group && chat != null) header = "👥 " + chat + (sender != null ? " · " + sender : "");
            else header = "💬 " + (sender != null ? sender : chat != null ? chat : "VK");
            this.text = text;
            fp = sha(pkg + '\u0001' + chat + '\u0001' + sender + '\u0001' + text + '\u0001' + time);
        }
    }

    private static Relay instance;

    static synchronized Relay get(Context c) {
        if (instance == null) instance = new Relay(c.getApplicationContext());
        return instance;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vk2tg", Context.MODE_PRIVATE);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>(); // fingerprint → first seen
    private ScheduledFuture<?> retry;
    private int failures;

    private Relay(Context ctx) {
        this.ctx = ctx;
        prefs = prefs(ctx);
        try {
            JSONObject s = new JSONObject(prefs.getString(K_SEEN, "{}"));
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                seen.put(k, s.getLong(k));
            }
        } catch (JSONException ignored) {
        }
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                worker.execute(() -> failures = 0);
                flush();
            }
        });
    }

    /** New notification content from VK: forward whatever hasn't been forwarded yet. */
    void submit(final List<Msg> msgs) {
        worker.execute(() -> {
            long now = System.currentTimeMillis();
            JSONArray q = queue();
            int added = 0;
            for (Msg m : msgs) {
                if (seen.containsKey(m.fp)) continue;
                seen.put(m.fp, now);
                try {
                    q.put(new JSONObject().put("h", m.header).put("t", m.text));
                    added++;
                } catch (JSONException ignored) {
                }
            }
            if (added == 0) return;
            while (q.length() > MAX_QUEUE) q.remove(0); // bot misconfigured for days: keep the newest
            for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > SEEN_TTL || seen.size() > MAX_SEEN) it.remove();
            }
            prefs.edit().putString(K_QUEUE, q.toString()).putString(K_SEEN, new JSONObject(seen).toString()).commit();
            Log.i(TAG, "queued " + added + ", queue " + q.length());
            drain();
        });
    }

    /** Try to send the queue now: config changed, network is back, listener (re)connected. */
    Future<?> flush() {
        return worker.submit(this::drain);
    }

    private void drain() {
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        String token = prefs.getString(K_TOKEN, ""), chat = prefs.getString(K_CHAT, "");
        JSONArray q = queue();
        while (q.length() > 0) {
            if (token.isEmpty() || chat.isEmpty()) {
                error("не заданы токен или чат, сообщения копятся в очереди");
                return;
            }
            StringBuilder text = new StringBuilder();
            JSONArray entities = new JSONArray();
            int n = batch(q, text, entities);
            Telegram.Result r = Telegram.send(token, chat, text.toString(), entities);
            if (r.ok) {
                q = drop(q, n);
                failures = 0;
                prefs.edit().putString(K_QUEUE, q.toString()).putLong(K_SENT, prefs.getLong(K_SENT, 0) + n)
                        .putLong(K_LAST_OK, System.currentTimeMillis()).putString(K_ERROR, "").commit();
                Log.i(TAG, "sent " + n + ", queue " + q.length());
                continue;
            }
            if (r.migrateTo != null) { // group became a supergroup, its id changed
                chat = r.migrateTo;
                prefs.edit().putString(K_CHAT, chat).commit();
                continue;
            }
            if (r.http == 400 && !r.error.toLowerCase(Locale.ROOT).matches(".*(chat|rights|admin|member|topic).*")) {
                // Telegram refuses this content itself — don't let it jam the queue forever
                q = drop(q, n);
                prefs.edit().putString(K_QUEUE, q.toString()).commit();
                error("пропущено " + n + " сообщ.: " + r.error);
                continue;
            }
            failures++;
            long delay = r.retryAfter > 0 ? r.retryAfter : Math.min(300, 5L << Math.min(failures - 1, 6));
            error(r.error + " — повтор через " + delay + " с");
            retry = worker.schedule(this::drain, delay, TimeUnit.SECONDS);
            return;
        }
    }

    /** Packs leading queue items into one message; repeated headers (same sender in a row) are merged. */
    private static int batch(JSONArray q, StringBuilder text, JSONArray entities) {
        int n = 0;
        String prev = null;
        try {
            for (; n < q.length(); n++) {
                JSONObject o = q.getJSONObject(n);
                String h = o.getString("h"), t = o.getString("t");
                boolean same = h.equals(prev);
                int len = (n > 0 ? 2 : 0) + (same ? 0 : h.length() + 1) + t.length();
                if (n > 0 && text.length() + len > BATCH_CHARS) break;
                if (n > 0) text.append(same ? "\n" : "\n\n");
                if (!same) {
                    entities.put(new JSONObject().put("type", "bold").put("offset", text.length()).put("length", h.length()));
                    text.append(h).append('\n');
                }
                text.append(t);
                prev = h;
            }
        } catch (JSONException ignored) {
        }
        return Math.max(n, 1);
    }

    String test() {
        return say("✅ VK → TG на связи (" + Build.MANUFACTURER + " " + Build.MODEL + ")");
    }

    /** Sends a line right away, bypassing the queue. Blocking — call off the main thread. */
    String say(String line) {
        String token = prefs.getString(K_TOKEN, ""), chat = prefs.getString(K_CHAT, "");
        if (token.isEmpty() || chat.isEmpty()) return "сначала задай токен и чат";
        Telegram.Result r = Telegram.send(token, chat, line, null);
        if (r.migrateTo != null) {
            prefs.edit().putString(K_CHAT, r.migrateTo).commit();
            r = Telegram.send(token, r.migrateTo, line, null);
        }
        if (r.ok) flush();
        return r.ok ? "ок, проверь чат в Telegram" : r.error;
    }

    String status() {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        boolean granted = nm.isNotificationListenerAccessGranted(new ComponentName(ctx, VkListener.class));
        boolean unrestricted = pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        long connected = prefs.getLong(K_CONNECTED, 0), lastOk = prefs.getLong(K_LAST_OK, 0);
        String chat = prefs.getString(K_CHAT, ""), err = prefs.getString(K_ERROR, "");
        StringBuilder s = new StringBuilder()
                .append("Доступ к уведомлениям: ").append(granted ? "есть" : "НЕТ")
                .append(granted && connected > 0 ? " (подключён " + time(connected) + ")" : "")
                .append("\nФон: ").append(unrestricted ? "без ограничений" : "батарея ограничивает — может засыпать")
                .append("\nБот: ").append(prefs.getString(K_TOKEN, "").isEmpty() ? "не задан" : "задан")
                .append(", чат: ").append(chat.isEmpty() ? "не задан" : chat)
                .append("\nПереслано: ").append(prefs.getLong(K_SENT, 0))
                .append(lastOk > 0 ? ", последнее " + time(lastOk) : "")
                .append("\nВ очереди: ").append(queue().length());
        if (prefs.getBoolean(K_SHELL, false)) s.append("\nОтладка: пересылаются и уведомления adb shell");
        if (!err.isEmpty()) s.append("\nОшибка (").append(time(prefs.getLong(K_ERROR_AT, 0))).append("): ").append(err);
        return s.toString();
    }

    private void error(String e) {
        prefs.edit().putString(K_ERROR, e).putLong(K_ERROR_AT, System.currentTimeMillis()).commit();
        Log.w(TAG, e);
    }

    private JSONArray queue() {
        try {
            return new JSONArray(prefs.getString(K_QUEUE, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static JSONArray drop(JSONArray q, int n) {
        for (int i = 0; i < n && q.length() > 0; i++) q.remove(0);
        return q;
    }

    private static String time(long ms) {
        return new SimpleDateFormat("d MMM HH:mm", new Locale("ru")).format(new Date(ms));
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
