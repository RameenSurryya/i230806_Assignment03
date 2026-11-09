# Kinnect – rules for Claude Code

Android app (package com.rameen.i230806). 23 screens copied from design/screens/screen-NN.png
(Assignment 1), connected to a Firebase backend (Assignment 2, see README.md).

Read first: @docs/SCREENS.md @docs/DESIGN_TOKENS.md @docs/ICONS.md @docs/PROGRESS.md

## Stack

- Kotlin + XML Views, ViewBinding. One Activity per screen. Basic layouts only.

- Packages: ui/auth, ui/home, ui/social, ui/profile, ui/chat, ui/market (see SCREENS.md).

- Never edit anything inside design/.

## Layout rules (try earlier options first)

1. LinearLayout (layout_weight for columns) is the default.

2. FrameLayout for anything that overlaps (badges, "+4" over a photo, dim overlays).

3. RelativeLayout for simple positioning (top bars).

4. GridLayout for true grids (photo picker, marketplace, shortcuts).

5. ScrollView / HorizontalScrollView; RecyclerView for lists filled from the database.

6. Basic widgets only: TextView, ImageView, Button, EditText, CheckBox, RadioGroup, Spinner, ProgressBar, View.

7. Styling via drawable shapes and styles.xml. No custom views, no layout libraries.

8. Rotation: android:rotation. Enlarging: scaleX/scaleY.

## Responsive rules (phones only)

Follow these in every screen. Tablets and landscape are out of scope; the app is portrait phones only.

- Design baseline is 360dp wide. Never hardcode a screen width in dp. Use match_parent,
  layout_weight and wrap_content so layouts stretch cleanly on phones from 320dp to 430dp wide.
- Fixed dp sizes only for things that should stay the same physical size: icons, avatars,
  button and input heights, badges, corner radii.
- Every screen has a scrolling body (ScrollView) with the top bar and any bottom bar pinned.
- Photos use scaleType centerCrop inside weighted or fixed-ratio containers, never fixed pixel widths.
- Text sizes in sp. Long text wraps or uses maxLines plus ellipsize.
- Handle the status bar and gesture bar with android:fitsSystemWindows="true" on each screen root,
  keeping the design's background colour behind the system bars.
- Lock the app to portrait with android:screenOrientation="portrait" on every Activity in
  AndroidManifest.xml.
- Test target: a 360x800dp emulator for pixel matching, plus one other phone size for the
  complex screens.

## Scope (Assignment 2: Firebase backend)

Assignment 1 was UI only. Assignment 2 connects the same screens to Firebase. Follow these now:

- Backend: Firebase Auth (email/password), Realtime Database, Cloud Messaging. Free Spark plan
  only: no Firebase Storage, no Cloud Functions, nothing that needs Blaze.
- Photos and videos are Base64 text under media/ (util/Images.kt). Videos at most 15 s / 5 MB.
- Every database path lives in data/Db.kt; every stored class in data/Models.kt. Screens use the
  repos in data/ (FriendRepo, PostRepo, StoryRepo, ChatRepo, CallRepo) for writes.
- Live data: BaseActivity.watch() (attached in onStart, removed in onStop). Lists: RecyclerView.
- Several related writes go in one multi-path updateChildren; counters use ServerValue.increment.
- Approved libraries: Firebase BoM, CameraX, Agora RTC, RecyclerView. Ask before adding others.
- Secrets never go in Git: app/google-services.json, app/src/main/assets/service-account.json,
  app/src/main/assets/agora-certificate.txt (all in .gitignore).
- Security rules: database.rules.json (same as published in the Firebase console). Keep them in
  sync with any new path or query (.indexOn).
- Runtime permissions (camera, microphone, gallery, notifications) are asked on the screen that
  needs them.
- New text in values/strings_<step>.xml, new sizes in values/dimens_<step>.xml.

## Naming

- Activity: XxxActivity. Layout: activity_xxx.xml. Included layouts: include_xxx.xml.

- View ids snake_case with prefix: btn_, tv_, iv_, et_, ll_, fl_, rg_ (e.g. btn_login).

- Drawables: ic_ (icons), bg_ (shapes), ph_ (vector photo placeholders), photo_ (real photos).

- All visible text in strings.xml. All colours/sizes in colors.xml / dimens.xml / styles.xml.

## Icons and images

- Icons are VectorDrawables in res/drawable, tinted with app:tint. Never invent a substitute: if an

  icon is missing, add it to docs/ICONS.md and tell me.

- Real photos go in res/drawable-nodpi (JPG, under 300 KB each). Initials avatars stay as styled TextViews.

## Navigation

- Explicit Intents. Back must return to the previous screen.

- Splash and Login call finish() after moving on.

- Top tabs use FLAG_ACTIVITY_REORDER_TO_FRONT so they don't pile up.

- Log out: FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK to LoginActivity.

## Quality

- Scrollable roots, 48dp minimum touch targets, contentDescription on icon buttons, readable contrast.

- Comment the code clearly: the author must explain it in a live demo. Keep code simple.

- On Windows the Bash tool can hang. Run builds and scripts through PowerShell (.\gradlew.bat assembleDebug, python script.py), and never use heredocs (<<'EOF'); write the script to a file first.

## Build effort and tolerance

Follow these for every screen.

- Goal: each screen must look like its PNG at a glance and side by side, about 99% the same.
  Accept differences of up to 2dp in position or size, up to 1sp in text size, and colours
  within a few shades. Do not chase anything smaller.
- Use the existing tokens, styles, drawables and includes. Do not re-measure things that are
  already in dimens.xml, colors.xml or DESIGN_TOKENS.md. Only measure what is new on this screen.
- Do not write new measuring, rendering or comparison scripts. Reuse the existing tools/ scripts
  only if they help, and never spend time on anti-aliasing, blur or compression noise.
- Verify with at most ONE round: build, install on the 360dp emulator, take one screenshot,
  compare it with the PNG, and fix only differences that are visible at normal size (wrong
  colour, wrong size, missing element, wrong overlap, wrong alignment, wrong order or spacing
  off by more than 2dp). Then stop.
- Do not test on a second emulator during screens. That is done once in the final responsive pass.
- Overlaps, rotations, badges and photo crops must be correct. Those are the visible things.
- Finish with a short list of at most 5 remaining differences, then commit and tell the user to
  push. Do not keep iterating after that.

## After every screen

1. Build with gradlew.bat assembleDebug (Command Prompt) and fix errors.

2. Tick the screen in docs/PROGRESS.md.

3. git commit with a meaningful message ("feat: add Login screen ..."), then git push.
