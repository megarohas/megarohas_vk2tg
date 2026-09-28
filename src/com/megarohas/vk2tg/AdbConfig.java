package com.megarohas.vk2tg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.concurrent.TimeUnit;

/**
 * Setup without typing on the tablet (only the adb shell may send this — see
 * the manifest):
 *   adb shell am broadcast -n com.megarohas.vk2tg/.AdbConfig --es token 123:ABC
 * Extras: token, chat (empty = detect), --ez test true, --es say <text>, --ez shell true|false
 * (debug: relay `cmd notification post` too). Replies with the status.
 */
public class AdbConfig extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, final Intent in) {
        final Context c = context.getApplicationContext();
        final PendingResult res = goAsync();
        new Thread(() -> {
            String reply;
            try {
                reply = handle(c, in);
            } catch (Exception e) {
                reply = "ошибка: " + e.getMessage();
            }
            res.setResultData(reply);
            res.finish();
        }).start();
    }

    private static String handle(Context c, Intent in) throws Exception {
        SharedPreferences p = Relay.prefs(c);
        SharedPreferences.Editor e = p.edit();
        if (in.hasExtra("token")) e.putString(Relay.K_TOKEN, String.valueOf(in.getStringExtra("token")).trim());
        if (in.hasExtra("chat")) e.putString(Relay.K_CHAT, String.valueOf(in.getStringExtra("chat")).trim());
        if (in.hasExtra("shell")) e.putBoolean(Relay.K_SHELL, in.getBooleanExtra("shell", false));
        e.commit();

        StringBuilder out = new StringBuilder();
        String token = p.getString(Relay.K_TOKEN, "");
        if (!token.isEmpty() && p.getString(Relay.K_CHAT, "").isEmpty()) {
            Telegram.Chat chat = Telegram.findChat(token);
            if (chat == null) return "бот пока не видит ни одного чата: добавь его в группу, напиши там /start и повтори";
            p.edit().putString(Relay.K_CHAT, chat.id).commit();
            out.append("найден чат «").append(chat.title).append("» (").append(chat.id).append(")\n");
        }
        if (in.getBooleanExtra("test", false)) out.append("тест: ").append(Relay.get(c).test()).append('\n');
        if (in.hasExtra("say")) out.append("отправка: ").append(Relay.get(c).say(String.valueOf(in.getStringExtra("say")))).append('\n');
        try { // report the state after the queue went out, not before
            Relay.get(c).flush().get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return out.append(Relay.get(c).status()).toString();
    }
}
