package com.megarohas.vk2tg;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.megarohas.vk2tg.Relay.TAG;

/** Watches VK notifications and hands every new chat message to the Relay. */
public class VkListener extends NotificationListenerService {

    static final Set<String> VK = new HashSet<>(Arrays.asList(
            "com.vk.im",               // VK Мессенджер
            "com.vkontakte.android")); // VK

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "listener connected");
        Relay.prefs(this).edit().putLong(Relay.K_CONNECTED, System.currentTimeMillis()).apply();
        try { // whatever arrived while we were down is still in the shade
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
        } catch (Exception e) {
            Log.w(TAG, "active notifications", e);
        }
        Relay.get(this).flush();
    }

    @Override
    public void onListenerDisconnected() {
        Log.w(TAG, "listener disconnected, asking to rebind");
        try {
            requestRebind(new ComponentName(this, VkListener.class));
        } catch (Exception ignored) { // access was revoked
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            boolean test = pkg.equals("com.android.shell") && Relay.prefs(this).getBoolean(Relay.K_SHELL, false);
            if (!VK.contains(pkg) && !test) return;
            List<Relay.Msg> msgs = parse(sbn);
            Log.i(TAG, "vk " + describe(sbn) + " -> " + msgs.size() + " msg");
            if (!msgs.isEmpty()) Relay.get(this).submit(msgs);
        } catch (Throwable t) {
            Log.e(TAG, "onNotificationPosted", t);
        }
    }

    /**
     * Messaging apps re-post one notification per conversation with the whole
     * unread history in it; each message is emitted separately with its own
     * timestamp, so Relay's dedup forwards only the ones not seen before.
     */
    static List<Relay.Msg> parse(StatusBarNotification sbn) {
        List<Relay.Msg> out = new ArrayList<>();
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE;
        if ((n.flags & skip) != 0) return out; // "3 new messages" summaries, calls in progress, sync
        Bundle x = n.extras;
        String pkg = sbn.getPackageName();
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String conv = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String self = str(x.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME));
        boolean group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || conv != null;
        String chat = conv != null ? conv : title;

        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            for (Parcelable p : messages) {
                if (!(p instanceof Bundle)) continue;
                Bundle m = (Bundle) p;
                String sender = str(m.getCharSequence("sender"));
                if (sender != null && sender.equals(self)) continue; // our own reply
                String text = str(m.getCharSequence("text"));
                if (text == null && m.containsKey("uri")) text = "[вложение]";
                if (text == null) continue;
                long time = m.getLong("time");
                out.add(new Relay.Msg(pkg, chat, sender != null ? sender : title, text, time != 0 ? time : n.when, group));
            }
            return out;
        }
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text == null) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        if (text != null) out.add(new Relay.Msg(pkg, chat, null, text, n.when, group));
        return out;
    }

    /** Shape of a notification for the log — which fields are there, never their contents. */
    static String describe(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        Parcelable[] m = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        StringBuilder s = new StringBuilder("id=").append(sbn.getId())
                .append(" ch=").append(n.getChannelId())
                .append(" cat=").append(n.category)
                .append(" flags=0x").append(Integer.toHexString(n.flags))
                .append(" style=").append(x.getString("android.template", "-").replace("android.app.Notification$", ""))
                .append(" group=").append(x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_SELF_DISPLAY_NAME}) {
            CharSequence v = x.getCharSequence(k);
            if (v != null) s.append(' ').append(k.replace("android.", "")).append('(').append(v.length()).append(')');
        }
        if (lines != null) s.append(" lines=").append(lines.length);
        if (m != null) {
            s.append(" messages=").append(m.length);
            if (m.length > 0 && m[0] instanceof Bundle) s.append(" msgKeys=").append(new java.util.TreeSet<>(((Bundle) m[0]).keySet()));
        }
        return s.toString();
    }

    private static String str(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
