# i230806 – Kinnect

Kinnect is a social networking app for Android (package `com.rameen.i230806`).
Assignment 1 recreated the 23 screens of the design (`design/Kinnect_UI.pdf`); Assignment 2
connected them to Firebase. **Assignment 3 replaces Firebase Auth and the Realtime Database
with our own PHP web services and a MySQL database**, adds an **SQLite offline cache** with a
queue of actions that are sent automatically when the internet comes back, and keeps Firebase
Cloud Messaging only to deliver push notifications, which the PHP server now sends.

## Setup

### 1. Server (XAMPP: Apache + PHP 8 + MySQL/MariaDB)

1. Put the `backend/` folder where Apache serves it, e.g. a junction
   `E:\xampp\htdocs\kinnect -> E:\kinnect\backend` (base path `/kinnect`).
2. Create the database `kinnect` and a MySQL user `kinnect` with a password, then the tables:
   `mysql -u root kinnect < backend/schema.sql` (19 tables).
3. Copy `backend/config/config.example.php` to `backend/config/config.php` and fill in the
   database password and the Agora App ID.
4. Put the server's secret files in `backend/secrets/`:

| File | Where it comes from | Used for |
|---|---|---|
| `service-account.json` | Firebase console → Project settings → Service accounts → Generate new private key | Sending push notifications (FCM HTTP v1) |
| `agora-certificate.txt` | Agora console → project → Primary Certificate (32 characters) | Making Agora call tokens |
| `cacert.pem` | https://curl.se/ca/cacert.pem | Trusted certificates for PHP's HTTPS calls to Google (XAMPP has none) |

5. Check it: open `http://<laptop IP>/kinnect/health.php` on the phone.

### 2. App

- `app/google-services.json` (Firebase console → Your apps), only for receiving pushes.
- `app/src/main/res/values/server.xml`: the server address, e.g. `http://192.168.100.68/kinnect/`.
  `res/xml/network_security_config.xml` allows plain HTTP only to that address and the emulator.
- `app/src/main/res/values/agora.xml`: the Agora App ID (the same as in `config.php`).

**Not in Git** (`.gitignore`): `google-services.json`, `backend/config/config.php` (database
password), `backend/secrets/*` (service account, Agora certificate) and `backend/uploads/*`.

## Build and run

```
.\gradlew.bat assembleDebug        # APK in app\build\outputs\apk\debug\
.\gradlew.bat installDebug         # install on the running emulator or phone
```

The phone and the laptop must be on the same Wi-Fi. Calls need two devices.

## Features

| Area | What works |
|---|---|
| Accounts | Sign up (password strength meter), login with a recent-login card, 5-second splash that opens Home **even offline** when a session token is saved, first-time profile setup, log out that invalidates the token on the server, marks the user offline, clears SQLite and the back stack |
| Profiles | Edit profile (name, unique username checked by the server, bio, details, profile and cover photo uploaded to the server), own and other profiles with mutual friends and photos |
| Friends | Send / cancel / accept / decline requests, unfriend, close friends, suggestions; search with **Everyone / Friends / Requests** filters |
| Feed | Text and photo posts (up to 10 photos), six reactions, comments with replies, likes and photo comments, share to chat; **posts and comments written offline show "Waiting to send…" and are sent later** |
| Stories | Photo or video, text sticker, public or close friends; **expire after 24 hours on the server and in SQLite**; seen-by list and reactions |
| Notifications | Friend requests, reactions, comments, replies, comment likes, story reactions, with a push for each |
| Chat | Text, **photos, videos and files**, shared posts, replies, love reactions, typing, seen, screenshot alert; **edit / delete within 5 minutes, enforced by the server**; offline messages are queued |
| Presence | Online while the app is open (heartbeat every minute), offline in the background or 2 minutes after the last heartbeat |
| Push | New messages, incoming calls and notifications, **sent from PHP** through FCM |
| Calls | Voice and video with Agora; ringing, answer, decline, end and the **call log** go through PHP; the Agora token is made on the server |
| Marketplace | Listings from the server, Sell form (works offline) |

## How it works

### Server: `backend/`

```
lib/        db.php (PDO), api.php (JSON answers, login token, once()), upload.php (files),
            users, friends, posts, stories, chat, calls, agora (tokens), push (FCM)
auth/       signup, login, logout, me, presence, fcm_token
profile/    update (fields + photo + cover)
users/      profile          friends/   action, lists          search.php
posts/      feed, get, create, delete, react, share        comments/  list, add, like, delete
stories/    feed, create, view, react, delete              notifications/  list, read, delete
chat/       list, messages, send, edit, delete, react, read, typing
calls/      start, status, answer, decline, end, log       market/   list, create
badges.php  health.php  schema.sql
```

- Every answer is JSON: `{"ok": true, ...}` or `{"ok": false, "error": "..."}` with an HTTP code.
- Every query uses PDO prepared statements.
- **Login token:** login returns a random 64-character token. The app sends it as
  `Authorization: Bearer ...`, and the server stores only its SHA-256 hash (table `sessions`).
  Logging out revokes it.
- **No duplicates:** actions from the offline queue carry a UUID in `X-Request-Id`. `once()`
  runs the action and saves its answer in `processed_requests` in one transaction, so a repeat
  returns the saved answer and does nothing twice.
- **Server rules:**
  - the 5-minute edit and delete limit for messages;
  - the 24-hour story expiry, which also deletes the files;
  - close-friends stories go only to the author's close friends;
  - usernames are unique;
  - you can only delete your own posts, comments and stories.
- **Files:** uploads go in `uploads/<folder>/` under random names, with type and size checks
  (25 MB). PHP is not executed in that folder.

### App: `app/src/main/java/com/rameen/i230806/`

```
data/   Api (Volley), SessionStore (encrypted token), LocalDb (SQLite), Outbox + SyncWorker
        (offline queue), NetworkMonitor, Cached (saved answer first, then the server), Json,
        AuthRepo, FriendRepo, PostRepo, StoryRepo, ChatRepo, CallRepo, Presence, Db, Models
push/   PushSender (sends this phone's FCM token), KinnectMessagingService (shows pushes)
util/   Images (Glide, photo compression), Avatars, Times, RecentLogin
ui/     one Activity per screen (auth, home, social, profile, chat, market)
```

- **SQLite first:** every screen shows its saved copy at once, so it also works offline, and
  then asks the server. Lists and profiles are cached by `Cached`, while posts, comments,
  stories, chats and users have their own tables in `LocalDb`.
- **Offline writes:**
  1. `Outbox.send` sends straight away when online.
  2. Otherwise the action goes into `pending_actions` with a UUID and copies of its files.
  3. `SyncWorker` (WorkManager, needs a network) sends the queue in order when the internet
     returns. `NetworkMonitor` also restarts it on reconnect.
  4. Items made offline get a local id (`local-<uuid>`) and show as pending until the server's
     copy replaces them.
- **Live data:** the open Chat screen asks the server every 2 seconds for what changed since
  its last answer. Chats asks every 5 seconds, badges every 20 seconds, and calls every
  1.5 seconds.

### Libraries

Volley 1.2.1, Glide 4.16.0, WorkManager 2.10.0, AndroidX Security Crypto 1.1.0, Firebase
Cloud Messaging (BoM 34.19.0), CameraX 1.6.2, Agora RTC 4.6.4, RecyclerView, Material, Espresso.

## Espresso tests

```
.\gradlew.bat connectedDebugAndroidTest
```

Both tests use a real account on the server (`espresso.tester@kinnect.app`, created on the
first run), so XAMPP must be running and reachable from the device.

- `HomeToCommentsTest`: Home → Create post → the post appears in the feed → Comment → write a
  comment → it appears → Back → Home. The test post is deleted afterwards.
- `LoginLogoutBackStackTest`: type email and password → Home → Menu → Log out → Login; Back
  closes the app and no session token is left.

## tools/ scripts

Python scripts used in Assignment 1 to measure the design PNGs. They are not part of the app.
