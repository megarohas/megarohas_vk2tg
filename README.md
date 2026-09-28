# VK → TG

**English** | [Русский](README.ru.md)

A tiny Android app that forwards new VK Messenger messages to a Telegram chat.
It reads VK's notifications (NotificationListenerService) and posts each new
message through your own Telegram bot. No VK API, no server, no root: an old
tablet with VK Messenger logged in becomes the bridge.

```
💬 Ivan Petrov
see you at 7?

👥 Work chat · Maria
the report is in the doc
```

## Features

- **Who wrote what.** The sender and the text are taken from each message
  inside VK's notification. Group chats get the chat name as well.
- **No duplicates.** VK re-posts one notification per chat with the whole
  unread history in it. Each message is fingerprinted (chat, sender, text,
  timestamp), so it is forwarded exactly once, even across restarts.
- **Nothing gets lost.** Messages go into a persistent queue. When Telegram
  or the network is unavailable, the app retries with backoff (5 s → 5 min),
  again as soon as the network comes back, and again whenever the listener
  reconnects. A backlog is packed into as few Telegram messages as possible.
- **Tiny.** A 29 KB APK in pure Java with no dependencies and no Gradle.
  Everything runs on framework APIs.
- **Private.** The bot token lives only in the app's private storage (backup
  disabled) and is scrubbed from every error message. The log records the
  shape of each notification, never the message text.

Watches `com.vk.im` (VK Мессенджер) and `com.vkontakte.android` (VK).

## Install

**From Releases.** Download `vk2tg.apk` from
[Releases](https://github.com/megarohas/megarohas_vk2tg/releases) and install it.
On Android 13+, apps installed from a file are blocked from notification
access at first. Before granting access, open *App info → ⋮ → Allow restricted
settings*.

**From source** (Android SDK with build-tools 36 and platform 36, JDK 17):

```bash
make                                   # aapt2 → javac → d8 → zipalign → apksigner, a few seconds
export ADB_SERIAL=<tablet-ip>:<port>   # wireless debugging
make install grant                     # install + notification access + no battery limits
```

`make grant` does over adb what you would otherwise do in Settings: it grants
notification access and whitelists the app from battery optimizations, so the
network stays up in Doze.

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token.
2. Send `/start` to the bot in a private chat, or add the bot to a group and
   send `/start` there.
3. Open **VK → TG**, paste the token, then tap **Найти чат** (find chat) and
   **Тест** (test). A "✅ VK → TG на связи" message should arrive.

Or configure everything from a computer without touching the tablet:

```bash
adb -s $ADB_SERIAL shell am broadcast -n com.megarohas.vk2tg/.AdbConfig --es token '<TOKEN>' --ez test true
```

Supported extras:

| Extra | Effect |
| --- | --- |
| `token` | Sets the bot token. |
| `chat` | Sets the chat id. An empty value detects the chat again. |
| `--ez test true` | Sends the test line. |
| `--es say <text>` | Sends exactly this text. |
| `--ez shell true` | Debug: also relays notifications posted with `cmd notification post`. |

Every call replies with the current status. The receiver is protected by
`android.permission.DUMP`. Only the adb shell holds that permission, so no
other app on the device can swap the token or redirect your messages.

## Notes

- VK Messenger must be logged in, and message previews must stay enabled in
  its notification settings. Otherwise all the app can forward is
  "Новое сообщение".
- VK doesn't notify while its app is open on the screen. Keep the tablet on
  the home screen or on a clock.
- After a reboot, Android re-binds the listener by itself. The app never
  needs to be opened again.

## Debug

```bash
make status   # access, battery, queue, last error
make log      # logcat: one line per VK notification, sends and retries
```
