# FitAgent — Phone test plan

Tick each box as you go. Where a test fails, note what you saw (a screenshot helps) next to it, then send this file
back, along with [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md) filled in for your Noise and boAt watches.

**Priority:** Part A (watch data) matters most: it tells us whether Noise/boAt data reaches FitAgent at all.
Parts B–H can be done in any order. About 60–90 minutes in total; Part A alone takes about 20.

---

## 0. Setup (once)

### 0.1 Backend (on this laptop)
The FitAgent server is already running on **port 8010** (port 8000 is taken by the old HealthAgent server).

```bash
cd ~/Desktop/"SMART_HEALTH "/fitagent
API_PORT=8010 docker compose up -d          # start (already running now)
curl http://localhost:8010/health           # → {"status":"ok", ...}
API_PORT=8010 docker compose down            # stop when finished
```
- [ ] From the **phone's browser**, open `http://192.168.1.42:8010/health` and check that it shows `"status":"ok"`.
  If it doesn't load: phone and laptop must be on the same Wi-Fi, and the laptop firewall must allow port 8010
  (`sudo ufw allow 8010/tcp` if ufw is on).
- Optional, for the AI coach and food photos: put your keys in `fitagent/.env` (`ANTHROPIC_API_KEY=…`,
  `LANGSMITH_API_KEY=…`), then `API_PORT=8010 docker compose up -d` again. Without keys, the coach runs on rules
  and food photos say "AI coach is offline". That's expected.

### 0.2 Install the app
APK: `fitagent/app/build/outputs/apk/debug/FitAgent-debug.apk` (installs as "FitAgent", package `com.fitagent.app.debug`).
- [ ] Either connect the phone with USB debugging on and run `adb install -r fitagent/app/build/outputs/apk/debug/FitAgent-debug.apk`,
  or copy the APK to the phone and open it (allow "install unknown apps").
- [ ] Note your phone model and Android version: ______________________

### 0.3 Watch apps
- [ ] NoiseFit (or the Noise app your watch uses) installed, watch paired, synced today
- [ ] boAt Crest installed, watch paired, synced today
- [ ] In **each** watch app, look for *Health Connect* / *Google Fit* / *Connected apps* in settings and turn it on
  (note where you found it: __________). If there is no such option, write that down; it's an important result.
- [ ] Android 13 or older only: Health Connect installed from the Play Store (Android 14+ has it built in)

---

## A. Watch data (most important)

| # | Steps | Expected | ✔ |
|---|---|---|---|
| A0 | Fresh install, **no watch app sharing yet** → finish onboarding | Today shows only the **Connect your watch** checklist (plus mood, food and your own workouts). No readiness, steps, rings, sleep or charts, and no streak. The header chip says "No watch connected" | [ ] |
| A1 | Open FitAgent → finish onboarding → tap **Connect** on Today | Asks for "Physical activity", then the Health Connect screen lists the data types to allow (steps, sleep, heart rate, HRV, SpO₂…) | [ ] |
| A2 | Allow all → turn on sharing in NoiseFit / boAt Crest → sync the watch → back on Today, tap **Check again** | Checklist ticks all 3 steps, then Today fills in; the header chip shows the watch (model if reported) and "… ago" | [ ] |
| A3 | You → Health Connect → **Data health** | Lists your watch app(s) by name, with the watch model if the app reports it | [ ] |
| A4 | Data health → **Run check** (compatibility) | A grid of data types × apps with ✓ / – marks. **Screenshot this** and fill in the device test matrix | [ ] |
| A5 | Compare Today's steps with the watch app's steps for today | Within ~3 % (the watch app must have synced first) | [ ] |
| A6 | Compare last night's sleep (Today → Sleep card) with the watch app | Same night within ~10 min; stages shown only if the watch reports them | [ ] |
| A7 | Compare resting heart rate | Within ~2 bpm (if the watch shares heart rate) | [ ] |
| A8 | Turn the watch's Bluetooth off for a few hours → on → open the watch app → open FitAgent | Missing hours fill in, no doubled steps | [ ] |
| A9 | Restart the phone, then open FitAgent next morning | Last night's sleep and readiness are there | [ ] |
| A10 | No watch sharing: You → Health Connect → turn on **Count phone steps without a watch** → walk ~500 steps → ⟳ | Steps and progress charts appear from the phone sensor. Turn it off again and they disappear | [ ] |
| A12 | Stop syncing the watch for > 24 h (don't open its app) | Amber banner "<watch app> last synced … ago", chip turns amber; the last real data stays visible | [ ] |
| A11 | Leave the app closed for 1 h (Android 14+ with background access allowed) | Data health shows syncs about every 15 min; otherwise "Updates when you open the app" | [ ] |

Results to write down for each watch: which data types arrived (steps / sleep / sleep stages / heart rate /
resting HR / HRV / SpO₂ / workouts) → fill in [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md).

## B. Today & insights
- [ ] B1 Readiness card shows a score, a one-line reason and contributor bars; tapping opens **Readiness** (30-day chart, sleep-vs-readiness scatter, load chart)
- [ ] B2 Hourly steps bars: press and drag across them → a bubble shows the hour and steps, with a light vibration per bar
- [ ] B3 Tiles (distance, resting HR, HRV, floors) show sparklines; Resting HR / HRV open **Heart**
- [ ] B4 Sleep card → **Sleep**: hypnogram, stages, W/M/3M nights, bedtime consistency (only data your watch shares appears; empty cards explain why)
- [ ] B5 Light mode (phone setting) looks right too

## C. Workouts
- [ ] C1 Train → Run (go outside) → allow location → run/walk ≥ 1 km → Finish → summary shows the route trace, km splits and pace
- [ ] C2 During C1 the voice says time checks; the 🔊 button mutes it
- [ ] C3 Train → Tabata: screen colour switches WORK/REST, vibration + "Rest" / "Work. Round 2…" voice cues
- [ ] C4 Lock the phone mid-workout for 2 min, unlock → timer kept counting; notification "Recording your route" shown for outdoor types
- [ ] C5 *Only with a chest strap or a watch that broadcasts HR (not Noise/boAt):* player → "Connect heart-rate sensor" → live bpm + zone; summary shows avg HR and zones
- [ ] C6 Train → Dorm workouts → open a routine → animated figure, countdown, "Next:" during rests, voice names each move; finishing saves a workout
- [ ] C7 Library → tap an exercise → animation, how-to, mistakes, easier version
- [ ] C9 Train → Camera rep counter → allow camera → prop the phone, do 10 squats → count reaches 10 (± 1); a too-shallow squat shows "Go a little deeper"; Save adds a workout. Try push-ups side-on too. Note the count you got: ____
- [ ] C8 A workout detected by your watch app appears in history with "synced"; deleting it says "Hide" and it stays hidden after ⟳

## D. Exam mode & coach (sign in first: You → Cloud account → Server `http://192.168.1.42:8010/` → create account)
- [ ] D1 Train → Exam mode → add classes (or import an `.ics` from Google Calendar export) → week chart shows classes and free time
- [ ] D2 Add an exam in 2 days, set bedtime → Coach → Weekly plan → generate: sessions have start times inside free slots, all ≤ 30 min, exam day easy
- [ ] D3 Coach chat: "What should I do today?" → reply uses your numbers
- [ ] D4 Coach chat: "I get chest pain when I run" → safety message with emergency number (no workout advice)
- [ ] D5 You → Coach language → हिन्दी → ask "आज मुझे क्या करना चाहिए?" → reply in Hindi

## E. Mood, food, wellbeing
- [ ] E1 Today → mood check-in (tap an emoji, add tags) → Stress & mood screen shows the calendar
- [ ] E2 Breathe → Box 4-4-4-4, 1 min → circle grows/shrinks, vibration on each phase
- [ ] E3 Today → Food → "From list" → add Dal tadka + 2 roti → totals, protein bar and meal chart update
- [ ] E4 Food → Photo → take a picture of a meal → with an API key: an editable estimate; without: "AI coach is offline" message (both are correct)

## F. Squad, rewards, campus (needs a second account: a friend's phone, or sign out/in with another email)
- [ ] F1 Squad → Create challenge → Invite → share the message to yourself on WhatsApp → the link opens a page with the code (App Link auto-open needs the release setup; in debug the web page is expected)
- [ ] F2 Second account joins with the code → both appear on the leaderboard; send 🔥 kudos → shows on the other row
- [ ] F3 You → Campus → set hostel/college → Squad campus card shows "Add …" or groups (groups need 5+ people)
- [ ] F4 You → Rewards & campus perks → points balance and weekly chart (points need watch/phone-tracked step-goal days)
- [ ] F5 You → Share my weekly recap → image preview → Share opens the share sheet; sleep only appears when toggled on

## G. Widget & notifications
- [ ] G1 Long-press home screen → Widgets → FitAgent → add small and medium → shows readiness and rings; tapping opens the app
- [ ] G2 Allow notifications → next morning ~08:00 a "Readiness …" card; evening reminder only if your goal is within ~25 min
- [ ] G2b With no watch connected: at ~08:00 a single "Connect your watch" notification (at most once a day); no readiness, evening or study-break nudges. With a watch quiet > 24 h: "<watch app> hasn't synced since …"
- [ ] G1b Widget with no watch connected shows "Open FitAgent to connect your watch", no numbers
- [ ] G3 With classes in Exam mode, sit still ~1.5 h in a free slot on a weekday → "Time for a study break" (at most 3 a day, never during class)
- [ ] G4 About 30 min before your computed bedtime → "Wind down in 30 minutes"

## H. Admin dashboard (laptop)
```bash
cd ~/Desktop/"SMART_HEALTH "/fitagent
docker compose exec api python scripts/make_admin.py your-admin@email.com "Your College"
```
- [ ] H1 Open `http://localhost:8010/admin` → sign in with that account → shows "Charts appear once 10+ students…" (expected with few accounts)
- [ ] H2 Publish an official challenge → a student account with the same college sees it under Squad → "From your college"
- [ ] H3 Add a perk → student sees it under Rewards

## J. Onboarding details, daily PDF report & server banner (see [WHATSAPP_REPORTS.md](WHATSAPP_REPORTS.md))
- [ ] J1 Fresh install → onboarding asks for **name, age, gender, city, height, weight**, then **WhatsApp number** (with country code) and the daily-report opt-in. Continue stays disabled until name/age are filled and the number is valid
- [ ] J2 You → Appearance → System / Light / **Dark** switches the theme instantly (status-bar icons too); survives restart
- [ ] J3 You → Daily report → **Preview PDF** opens a one-page report; with no watch it says "No watch data for this day" (no made-up numbers)
- [ ] J4 **Share on WhatsApp** opens WhatsApp with the PDF attached (pick your own chat). Works with the server off
- [ ] J5 Stop the laptop server (`docker compose stop api`) → within ~15 s a red **Not connected to the FitAgent server** banner appears on every tab; start it again → **Retry** clears it
- [ ] J6 Signed in, server on, no WhatsApp keys: **Send today's report from the server now** → "WhatsApp isn't set up on the server yet"; `docker compose exec reports ls reports` lists the PDF
- [ ] J7 With WhatsApp keys + your number verified in Meta (WHATSAPP_REPORTS.md §4) → the PDF arrives on WhatsApp; a non-verified number shows error 131030
- [ ] J8 Leave the report on for a day → at the chosen hour (18/20/21/22:00) it arrives once, not twice

## I. Privacy & cleanup
- [ ] I1 You → Delete account → signed out; signing in again fails (account erased on the server)
- [ ] I2 Uninstall → reinstall: local data is gone (no cloud backup of health data)

---

### If something crashes
With USB connected, capture the log and send it:
```bash
adb logcat -d | grep -E "fitagent|AndroidRuntime" > fitagent-log.txt
```
