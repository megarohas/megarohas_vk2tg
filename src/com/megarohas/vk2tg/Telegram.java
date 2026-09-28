package com.megarohas.vk2tg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Bare-bones Telegram Bot API client: sendMessage and getUpdates, nothing else. */
final class Telegram {

    static final class Result {
        boolean ok;
        int http;             // HTTP status, -1 when there was no response at all
        String error = "";    // human-readable, token scrubbed
        long retryAfter;      // seconds, set on 429
        String migrateTo;     // new chat id after a group was upgraded to a supergroup
        JSONObject body;
    }

    static final class Chat {
        final String id, title;

        Chat(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** entities: bold ranges etc. — offsets in UTF-16 units, i.e. plain String.length(). */
    static Result send(String token, String chat, String text, JSONArray entities) {
        StringBuilder form = new StringBuilder()
                .append("chat_id=").append(enc(chat))
                .append("&text=").append(enc(text));
        if (entities != null && entities.length() > 0) form.append("&entities=").append(enc(entities.toString()));
        return call(token, "sendMessage", form.toString());
    }

    /**
     * The most recent chat the bot has heard from: a group it was added to or
     * where someone wrote /start, or a direct chat. Null if there is none yet.
     */
    static Chat findChat(String token) throws Exception {
        Result r = call(token, "getUpdates", null);
        if (!r.ok) throw new Exception(r.error);
        JSONArray updates = r.body.optJSONArray("result");
        if (updates == null) return null;
        String[] kinds = {"message", "channel_post", "my_chat_member", "edited_message", "edited_channel_post"};
        for (int i = updates.length() - 1; i >= 0; i--) {
            JSONObject u = updates.getJSONObject(i);
            JSONObject member = u.optJSONObject("my_chat_member");
            if (member != null) {
                JSONObject now = member.optJSONObject("new_chat_member");
                String st = now == null ? "" : now.optString("status");
                if (st.equals("left") || st.equals("kicked")) continue; // bot was removed from there
            }
            for (String k : kinds) {
                JSONObject o = u.optJSONObject(k);
                JSONObject chat = o == null ? null : o.optJSONObject("chat");
                if (chat != null) return new Chat(String.valueOf(chat.getLong("id")), title(chat));
            }
        }
        return null;
    }

    private static String title(JSONObject chat) {
        String t = chat.optString("title");
        if (t.isEmpty()) t = (chat.optString("first_name") + " " + chat.optString("last_name")).trim();
        if (t.isEmpty()) t = "@" + chat.optString("username");
        return t;
    }

    private static Result call(String token, String method, String form) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            if (form != null) {
                byte[] data = form.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            r.http = c.getResponseCode();
            InputStream in = r.http >= 400 ? c.getErrorStream() : c.getInputStream();
            try {
                r.body = new JSONObject(in == null ? "" : read(in));
            } catch (Exception e) {
                r.error = "HTTP " + r.http + ", ответ не от Telegram";
                return r;
            }
            r.ok = r.body.optBoolean("ok");
            if (!r.ok) {
                r.error = "HTTP " + r.http + ": " + r.body.optString("description");
                JSONObject p = r.body.optJSONObject("parameters");
                if (p != null) {
                    r.retryAfter = p.optLong("retry_after");
                    if (p.has("migrate_to_chat_id")) r.migrateTo = String.valueOf(p.getLong("migrate_to_chat_id"));
                }
            }
        } catch (Exception e) {
            r.http = -1;
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        if (!token.isEmpty()) r.error = r.error.replace(token, "***"); // exceptions may quote the URL
        return r;
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = is.read(b)) > 0; ) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Telegram() {
    }
}
