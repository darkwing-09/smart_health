# Getting Noise watch data into FitAgent

Tested on 8 Nov 2026 with a Noise Icon 2, NoiseFit 5.5.0, Google Fit 2026.09.24 and a vivo I2214 phone on Android 16.

## How the data travels

```
Noise Icon 2 ──BLE──► NoiseFit ──► Google Fit ──► Health Connect ──► FitAgent ──► server / PDF report
```

NoiseFit 5.5.0 writes **nothing directly to Health Connect**. It does hold Health Connect permissions, but we saw no
writes. It sends data only to **Google Fit**, and the **Google Fit app** passes it on to Health Connect.

## One-time setup on the phone
1. **NoiseFit**: sign in, pair the watch, and approve Android's prompt *"Allow NoiseFit to access Noise Icon 2?"*.
   Without this approval (Android's "companion device" link) every sync stops at that prompt, which looks like a crash.
   Clearing NoiseFit's data or logging out deletes the approval *and* NoiseFit's stored history.
2. **NoiseFit → watch icon → Google fit → Connect**, then finish the Google sign-in. The page then shows **Disconnect**.
   ("Pair" in the shortcut row beside it is a separate button for pairing a new device.)
3. **Install Google Fit** from Google Play (vivo's own store doesn't carry it), sign in with the same Google account,
   then turn on **Profile → Settings → Sync Fit with Health Connect** and allow all permissions.
4. **FitAgent**: allow all Health Connect permissions, including *Total calories burned*.

## What arrives, and how fast (measured)

| Data | NoiseFit | Google Fit | FitAgent |
|---|---|---|---|
| Heart rate | 85 → 90 bpm | 85 bpm, then 90 | 85 → 90 bpm (same values, a few minutes behind) |
| Calories | – | 56 kcal **total** (includes basal) | 3 kcal **active** (= 56 − 53 basal since midnight) |
| SpO₂ | 97 % | not received | not received: the Google Fit route drops SpO₂ |
| Steps | 0 just after midnight | 0 | 0 |

The delay depends on when NoiseFit syncs. Once data reaches Health Connect, FitAgent shows it within 60 s while it is
open, and every 15 min in the background.

## What FitAgent changed to support this
- Any app that delivers heart rate, resting HR, HRV or SpO₂ counts as a watch source, since a phone can't measure
  these. This makes data relayed by Google Fit count as watch data.
- FitAgent reads **Total calories burned** as well as active calories. When an app writes only the total, active
  energy = total − basal energy (Mifflin–St Jeor from the profile) for the part of the day covered. Today's query ends
  at "now", because Health Connect pads the total with basal energy for the whole window it's asked about.
- Today shows a **Heart rate** card with the latest reading, how old it is, and today's range.
- A day with only heart-rate data now counts as a day with watch data, both in the report and in the cloud sync.

## Limits
- Google supports Google Fit only until the end of 2026. After that, a Noise watch works only if NoiseFit writes to
  Health Connect itself.
- SpO₂, sleep stages and HRV from NoiseFit don't pass through Google Fit, so FitAgent can't show them on this route.
