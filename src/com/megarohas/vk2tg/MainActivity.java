package com.megarohas.vk2tg;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** The whole UI: status, bot token + chat, three buttons. Built in code — no layouts. */
public class MainActivity extends Activity {

    private TextView status;
    private EditText token, chat;
    private Button access, battery;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView h = new TextView(this);
        h.setText("VK → Telegram");
        h.setTextSize(26);
        col.addView(h);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(12), 0, dp(12));
        status.setTextIsSelectable(true);
        col.addView(status);

        access = button("Выдать доступ к уведомлениям", v -> openAccessSettings());
        col.addView(access);
        battery = button("Не ограничивать в фоне", v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        col.addView(battery);

        col.addView(label("Токен бота (от @BotFather)"));
        token = field(Relay.prefs(this).getString(Relay.K_TOKEN, ""), "123456789:AA…");
        col.addView(token);
        col.addView(label("ID чата"));
        chat = field(Relay.prefs(this).getString(Relay.K_CHAT, ""), "заполнится по кнопке «Найти чат»");
        col.addView(chat);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(button("Найти чат", v -> findChat()), weight());
        row.addView(button("Сохранить", v -> save(true)), weight());
        row.addView(button("Тест", v -> test()), weight());
        col.addView(row);

        TextView help = new TextView(this);
        help.setText("1. Создай бота у @BotFather и скопируй токен.\n"
                + "2. Добавь бота в группу, куда слать сообщения, и напиши там /start "
                + "(или просто напиши /start самому боту в личке).\n"
                + "3. Вставь токен, нажми «Найти чат», потом «Тест».\n"
                + "4. Выдай доступ к уведомлениям — новые сообщения из VK полетят в чат.");
        help.setPadding(0, dp(20), 0, 0);
        help.setAlpha(0.7f);
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private void refresh() {
        boolean granted = getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(this, VkListener.class));
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        access.setVisibility(granted ? View.GONE : View.VISIBLE);
        battery.setVisibility(unrestricted ? View.GONE : View.VISIBLE);
        status.setText(Relay.get(this).status());
    }

    private void openAccessSettings() {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, VkListener.class).flattenToString());
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void save(boolean announce) {
        Relay.prefs(this).edit()
                .putString(Relay.K_TOKEN, token.getText().toString().trim())
                .putString(Relay.K_CHAT, chat.getText().toString().trim())
                .apply();
        Relay.get(this).flush();
        if (announce) toast("Сохранено");
        refresh();
    }

    private void findChat() {
        final String t = token.getText().toString().trim();
        if (t.isEmpty()) {
            toast("Сначала вставь токен");
            return;
        }
        toast("Спрашиваю Telegram…");
        new Thread(() -> {
            String msg;
            Telegram.Chat found = null;
            try {
                found = Telegram.findChat(t);
                msg = found == null
                        ? "Бот пока не видит ни одного чата: добавь его в группу, напиши там /start и повтори"
                        : "Нашёл: «" + found.title + "». Теперь «Тест»";
            } catch (Exception e) {
                msg = e.getMessage();
            }
            final Telegram.Chat c = found;
            final String m = msg;
            runOnUiThread(() -> {
                if (c != null) {
                    chat.setText(c.id);
                    save(false);
                }
                toast(m);
            });
        }).start();
    }

    private void test() {
        save(false);
        new Thread(() -> {
            String r = Relay.get(this).test();
            runOnUiThread(() -> {
                toast(r);
                refresh();
            });
        }).start();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setPadding(0, dp(12), 0, 0);
        return v;
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);
        e.setHint(hint);
        return e;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
