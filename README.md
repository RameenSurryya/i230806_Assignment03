# i230806 – Kinnect

Kinnect is a social networking app for Android (package `com.rameen.i230806`).
Assignment 1 recreated the 23 screens of the design (`design/Kinnect_UI.pdf`); Assignment 2
connects them to a **Firebase backend** so that every screen shows live, real data: accounts,
friends, posts, comments, reactions, stories, notifications, chat, push notifications, voice
and video calls, and a marketplace. Everything runs on Firebase's free **Spark** plan.

## Setup (secret files are not in the repository)

Three files hold keys, so they are listed in `.gitignore` and must be added by hand:

| File | Where it comes from | Used for |
|---|---|---|
| `app/google-services.json` | Firebase console → Project settings → Your apps → download | Connecting the app to the Firebase project |
| `app/src/main/assets/service-account.json` | Firebase console → Project settings → Service accounts → Generate new private key | Sending push notifications (FCM HTTP v1) |
| `app/src/main/assets/agora-certificate.txt` | Agora console → project → Primary Certificate (32 characters) | Making Agora call tokens |

Also needed:

- Firebase **Authentication** with Email/Password enabled, and a **Realtime Database**
  (region asia-southeast1) with the rules from `database.rules.json` published.
- The Agora **App ID** in `app/src/main/res/values/agora.xml`.

Without `service-account.json` the app still works but sends no pushes; without the Agora
files calls show a message instead of connecting.

## Build and run

Android Studio (recent stable) with Android SDK 37, min SDK 26. Open the folder, wait for
Gradle sync and run the `app` configuration, or from PowerShell:

```
.\gradlew.bat assembleDebug        # APK in app\build\outputs\apk\debug\
.\gradlew.bat installDebug         # install on the running emulator or phone
```

Push notifications need an emulator image with Google Play. Calls need two devices.

## Features

| Area | What works |
|---|---|
| Accounts | Sign up with password strength meter, login with a "recent login" card, password reset email, 5-second splash that skips login when already signed in, first-time profile setup, log out that clears the back stack |
| Profiles | Edit profile (name, unique username, bio, website, work, study, city, profile and cover photo), own profile, other people's profiles with mutual friends and their photos |
| Friends | Send / cancel / accept / decline requests, unfriend, close friends, suggestions, search by name or username |
| Feed | Text and photo posts (up to 10 photos), six reactions with the reaction picker, comments with replies, likes and photo comments, sharing a post into a chat |
| Stories | CameraX photo and 15-second video, gallery pick, text sticker, public or close-friends audience, 24-hour expiry, viewer with progress bar, seen-by list, reactions and replies |
| Notifications | Friend requests, accepted requests, reactions, comments, replies, comment likes, story reactions; New / Earlier sections, unread badge on the bell |
| Chat | Live chats list with unread dots and online status, messages, photos, shared posts, replies, love reactions, edit and delete within 5 minutes, typing indicator, seen receipt, screenshot alert |
| Push | Notifications for new messages and incoming calls, even when the app is closed |
| Calls | Voice and video calls with Agora: ringing, answer / decline, mute, speaker, camera flip / off, call timer, call lines in the chat and call logs |
| Marketplace | Live listings in a two-column grid, Sell form, sample items added on a fresh database |

## How it works

### Package structure

```
app/src/main/java/com/rameen/i230806/
  KinnectApp.kt         app start: notification channels, server clock, online / offline status
  data/                 Models.kt (every stored class), Db.kt (every database path),
                        FriendRepo, PostRepo, StoryRepo, ChatRepo, CallRepo
  push/                 PushSender (sends pushes), KinnectMessagingService (receives them)
  util/                 Images (Base64 photos and videos), Avatars, Times, RecentLogin
  ui/BaseActivity.kt    shared parent: system bars, tabs, top bar, live badges, watch()
  ui/auth/ ui/home/ ui/social/ ui/profile/ ui/chat/ ui/market/   one Activity per screen
app/src/androidTest/    the two Espresso tests and their TestAccount helper
database.rules.json     the Realtime Database security rules (same as in the console)
```

### Database (Firebase Realtime Database)

```
users/{uid}                     profile, online status, push token
usernames/{username}            uid (makes usernames unique, claimed with a transaction)
friendRequests/{to}/{from}      time        friends/{uid}/{friendUid}   time (both sides)
closeFriends/{uid}/{friendUid}  true
posts/{postId}                  post with reactions        comments/{postId}/{commentId}
stories/{uid}/{storyId}         story with viewers and reactions, expiresAt = +24 h
media/{mediaId}                 Base64 photo or video
notifications/{uid}/{id}        rows of the Notifications screen
userChats/{uid}/{chatId}        one row per conversation (last message, unread count)
messages/{chatId}/{msgId}       typing/{chatId}/{uid}      chatReads/{chatId}/{uid}
calls/{calleeUid}               the call ringing now        callLogs/{uid}/{callId}
listings/{id}                   marketplace items
```

### Key decisions

- **Free plan only.** Firebase Storage and Cloud Functions need the paid Blaze plan, so:
  - photos are compressed (longest side 1080 px, JPEG 70) and stored as Base64 text under
    `media/`; posts and messages keep only the media id, so lists don't download every picture;
  - story videos are recorded at SD quality and limited to 15 seconds and 5 MB;
  - push notifications are sent by the app itself through the FCM HTTP v1 API, signing a
    Google access token with the service-account key (`PushSender`);
  - Agora call tokens are made in the app from the App Certificate (`AgoraToken`).
  A real product would move the two keys to a server; for this project they stay out of Git.
- **Live screens.** `BaseActivity.watch()` attaches database listeners in `onStart` and removes
  them in `onStop`, so hidden screens stop downloading. Lists use RecyclerView adapters.
- **Consistent writes.** Actions that touch several places (sending a message updates the
  message and both chat rows; accepting a request updates both friend lists) use one
  multi-path `updateChildren`, so the database is never half-updated. Counters use
  `ServerValue.increment`.
- **Server time.** `Db.now()` uses Firebase's clock offset, so the 5-minute edit window and the
  24-hour stories work the same on every phone.
- **Security rules.** Each person can only change their own data; chats are readable only by
  the two people in them; the 5-minute edit limit is also enforced by the rules
  (`database.rules.json`).
- **Design.** The Assignment 1 layouts, tokens, fonts and icons are kept; new screens (video
  call, incoming call, share to chat) reuse the same styles. Design notes are in `docs/`.

### Libraries

Firebase BoM 34.19.0 (Auth, Realtime Database, Cloud Messaging), CameraX 1.6.2, Agora RTC
full SDK 4.6.4, AndroidX RecyclerView, AppCompat, Material, Espresso.

## Espresso tests

```
.\gradlew.bat connectedDebugAndroidTest
```

Both tests use a real test account (`espresso.tester@kinnect.app`, created on the first run)
and wait for Firebase before checking the screen.

- `HomeToCommentsTest`: Home → Create post → the post appears in the feed → Comment → write a
  comment → it appears → Back → Home. The test post is deleted afterwards.
- `LoginLogoutBackStackTest`: type email and password → Home → Menu → Log out → Login; Back
  closes the app and nobody is logged in.

## tools/ scripts

Python scripts used in Assignment 1 to measure the design PNGs (colours, sizes, radii, fonts,
placeholders). They are not part of the app; see the comments at the top of each script.
