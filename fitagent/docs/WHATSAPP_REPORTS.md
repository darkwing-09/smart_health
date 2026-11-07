# Daily PDF report on WhatsApp

Each evening FitAgent can send the student a one-page PDF on WhatsApp. It covers readiness, steps, energy, workout
minutes, distance, sleep, resting heart rate, HRV, blood oxygen, 7-day steps and sleep charts, the day's workouts and
a tip for tomorrow. Only real watch data appears. A metric the watch doesn't share shows "—". A day without watch
data produces a one-line "No watch data for this day" report.

## 1. How the data gets to the server

```
Watch (Noise / boAt) ──BLE──► NoiseFit / boAt Crest app ──► Health Connect ──► FitAgent app (phone)
                                                                                    │  POST /v1/me/sync
                                                                                    ▼  (daily summaries, every ~15 min + on open)
                                                     FitAgent server: daily_activity, workouts tables
                                                                                    │
                       reports service (python -m app.jobs.daily_reports --loop, every 10 min)
                         └─ for each opted-in user whose local time ≥ report_hour and who hasn't had today's report:
                              build PDF (app/services/report.py) → WhatsApp Cloud API → record in report_deliveries
```

* The server never reads the watch directly. The **phone app pushes** daily summaries (`/v1/me/sync`), and the report
  uses what was last pushed. Intraday heart rate, sleep segments and GPS routes never leave the phone.
* Delivery is **idempotent**: one row per user per local day in `report_deliveries`. Only network failures are retried.
* The student's details come from onboarding (name, age, gender, city, WhatsApp number with country code, opt-in). They're
  stored on the phone and, once the student creates an account, on the server through `PATCH /v1/me`.

## 2. Before the backend is deployed

The app works without the server: watch data, Today, charts, workouts and the local PDF all run on the phone.

| Feature | No server | With server |
|---|---|---|
| Watch data, charts, readiness, workouts | ✓ | ✓ |
| Daily PDF, **Preview** and **Share on WhatsApp** (You → Daily report) | ✓, made on the phone; you pick the chat | ✓ |
| Automatic evening WhatsApp delivery | ✗ | ✓, once WhatsApp keys are set |
| Coach, Squad, cloud backup | ✗ | ✓ |

While the server can't be reached, every tab shows a red **"Not connected to the FitAgent server"** banner. It has
**Retry** and **Server** buttons, and the app re-checks `/health` every 15 s (every 60 s once connected).

On the laptop during development the server is `docker compose up -d`, and the phone uses `http://<laptop-ip>:8010/`.
For a public deployment, see §5.

## 3. Endpoints

| Method | Path | What it does |
|---|---|---|
| `GET` | `/v1/me/report.pdf?day=YYYY-MM-DD` | The PDF (default: today in the user's timezone) |
| `POST` | `/v1/me/report/send` | Send it now to the user's own number (5/hour) → `sent` / `dry_run` / `failed` |
| `GET` | `/v1/me/report/status` | Whether WhatsApp is configured, the number, opt-in, last delivery |
| `PATCH` | `/v1/me` | `whatsapp_number` (E.164), `whatsapp_opt_in`, `report_hour`, `city`, `gender` |

Without `WHATSAPP_TOKEN` the server runs in **dry run**: it builds the PDF and saves it to `REPORT_DIR` (the `reports`
Docker volume) instead of sending it.

## 4. Turning on real WhatsApp delivery (Meta WhatsApp Cloud API)

WhatsApp only allows automated messages through the official **WhatsApp Business Platform**. The Cloud API is free to
set up, and messages are billed per conversation by Meta.

1. Go to <https://developers.facebook.com> → *My apps* → **Create app** → type *Business* → add the **WhatsApp** product.
2. *WhatsApp → API setup* shows a **test phone number** and its **Phone number ID**. Copy the ID into
   `WHATSAPP_PHONE_NUMBER_ID`.
3. Under *To*, **add the recipient numbers** you want to test with (your own number, a friend's). Each one gets a
   code on WhatsApp to confirm. **The test number can only send to these verified numbers, up to 5.**
4. Create a **permanent token**: *Business settings → Users → System users → Add* (admin), then *Generate token* for the app
   with the `whatsapp_business_messaging` and `whatsapp_business_management` permissions. Put it in `WHATSAPP_TOKEN`. The
   temporary token on the API setup page expires after 24 h.
5. Create a **message template**: *WhatsApp Manager → Message templates → Create*, category **Utility**, name
   `daily_report`, language English.
   * Header: **Document**
   * Body: `Your FitAgent daily report is ready: {{1}}` (sample: `3 Nov: readiness 66 (Steady), 9,100 steps.`)
   * Wait for approval (usually minutes). Then set `WHATSAPP_TEMPLATE=daily_report` and `WHATSAPP_TEMPLATE_LANG=en`.
6. Restart: `API_PORT=8010 docker compose up -d`. You → Daily report → **Send today's report from the server now**
   should arrive on WhatsApp.

`.env` keys:
```
WHATSAPP_TOKEN=EAAG...            # system-user token
WHATSAPP_PHONE_NUMBER_ID=1234567890
WHATSAPP_TEMPLATE=daily_report    # empty = plain document message (only delivered within 24 h of the user messaging you)
WHATSAPP_TEMPLATE_LANG=en
```

### Can it send to any number?

| Stage | Who can receive |
|---|---|
| Test number (step 2–3) | Only the ≤ 5 numbers you verified. Others fail with error **131030** "Recipient phone number not in allowed list", which the app shows |
| Your own number + **business verification** in Meta Business Manager | **Any WhatsApp number** that opted in (our onboarding checkbox is the opt-in). Starts at 250 business-initiated conversations a day and rises automatically with good quality |

For production you need a phone number that isn't already on WhatsApp (a new SIM or a landline), and a verified
business: the college, or a registered student startup.

**Alternatives.** **Twilio WhatsApp** offers the same Meta platform with a simpler console. Its sandbox works only for
numbers that send it a join code, and production also needs Meta approval. Unofficial "WhatsApp Web" automation
libraries break WhatsApp's terms and get numbers banned, so we don't use them.

## 5. Deployment notes
* The `reports` service in `docker-compose.yml` runs the job. Keep exactly **one** instance running.
* The server must be reachable from phones over **HTTPS** with a public domain (e.g. Render, Railway, Fly.io or a VPS
  with Caddy). Meta's API is called outbound, so no webhook is needed to send.
* Privacy: the report is sent only to the student's own number and only if they opt in. Clearing the number turns it
  off, and *Delete account* erases the number and the delivery log.
