# Setting up payments (Razorpay) and reminder emails

Monthly (₹99) and Yearly (₹999) are paid through **Razorpay**: UPI, cards and
netbanking. Payment is **manual each period**: nothing is charged
automatically. A few days before each payment is due, the app emails a
reminder, and the Dashboard and Plan pages show a **Pay now** button.

Until Razorpay keys are set, the Plan page shows prices but says online payment
is not set up, and the pay buttons are disabled. The development plan switch
(`DEV_PLAN_SWITCH_ENABLED=true`) keeps working either way.

---

## 1. Test-mode keys (free, about 5 minutes)

Test mode takes fake payments: no real money moves, and you can sign up without
any business verification.

1. Sign up or sign in at the [Razorpay Dashboard](https://dashboard.razorpay.com/).
2. Switch to **Test Mode** (toggle at the top of the dashboard).
3. Go to **Account & Settings → API Keys → Generate Test Key**.
4. Copy the **Key Id** (starts with `rzp_test_`) and the **Key Secret**. The
   secret is shown only once; generate a new key if you lose it.
5. Put them in `url_shortenerV4/.env` (never commit this file):

   ```
   RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxxxx
   RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxxxxxxxxxx
   ```

6. Restart the backend: `docker compose up -d app`.

The key secret stays on the server. The browser only ever gets the key id,
which is public by design.

## 2. Make a test payment

1. Sign in, open **Plan**, and click **Pay ₹99** under Monthly.
2. Razorpay's payment window opens. Pay with test details from
   [Razorpay's test card and UPI list](https://razorpay.com/docs/payments/payments/test-card-details/).
   For example, UPI ID `success@razorpay` succeeds and `failure@razorpay` fails.
   For cards, use any future expiry date and any CVV.
3. After paying, the Plan page says **Payment received**. It shows Monthly with
   a renewal date one month away, and the payment appears under **Payments**.
   You can also see the payment in the dashboard under **Transactions → Payments**
   (test mode).

### What a payment buys

| Situation | New paid-until date |
|---|---|
| First payment, or paying after the plan lapsed | today + 1 month (or year) |
| Renewing early (Renew shows from 7 days before the due date) | current due date + 1 month (or year) |
| Paying during the 7-day grace period | due date + 1 month: late payment gains no extra days |
| Monthly → Yearly while Monthly is paid | Yearly starts now; current due date + 1 year |
| Yearly → Monthly while Yearly is paid | not allowed until the Yearly period ends |

The price always comes from the server (`BILLING_MONTHLY_PRICE_PAISE`,
`BILLING_YEARLY_PRICE_PAISE`, in paise: 9900 = ₹99), never from the browser.

## 3. Webhook (optional locally, required in production)

Normally the browser confirms the payment right after Checkout. If the user
closes the tab at the wrong moment, Razorpay's **webhook** confirms it instead.
Both paths are safe to run together: a payment is applied only once.

Razorpay must reach the webhook over the internet, so locally you need a tunnel,
for example `cloudflared tunnel --url http://localhost:8080` or
`ngrok http 8080`.

1. In the dashboard (Test Mode): **Account & Settings → Webhooks → Add New Webhook**.
2. **Webhook URL**: `https://<your-tunnel-host>/api/v1/billing/webhook`
3. **Secret**: make up a long random string.
4. **Active events**: `payment.captured` and `order.paid`.
5. Put the same secret in `.env` and restart the backend:

   ```
   RAZORPAY_WEBHOOK_SECRET=the-secret-you-chose
   ```

The webhook checks Razorpay's `X-Razorpay-Signature` header against that secret
and ignores anything that does not match. With no secret set, webhooks are
refused and only the browser confirmation is used.

## 4. Reminder emails

A daily job (09:00 server time, `BILLING_REMINDERS_CRON`) sends at most one
email of each kind per paid period:

| When | Email |
|---|---|
| 3 days before the due date | Your plan renews on … |
| On the due date | Payment due today |
| 3 days into the grace period | Pay by … to keep your plan |
| Last day of grace | Last day to keep your plan |
| Grace over (moved to Free) | Plan ended; your links stop on … |
| The day before those links stop | Links stop tomorrow |

Paying stops the remaining reminders, because the due date moves. If sending
fails, the reminder is tried again the next day. Users who signed in with GitHub
and have no public email get no emails, only the in-app banners.

**Locally**, all email goes to **Mailpit**, which comes with `docker compose`.
Open <http://localhost:8025> to read it. Nothing reaches real inboxes.

To try a reminder locally:

1. Make sure `DEV_PLAN_SWITCH_ENABLED=true` is set.
2. On the Plan page, use the dev switch to make a Monthly plan due in a couple
   of days, or one that is days late.
3. Run the job now instead of waiting for 09:00: temporarily set
   `BILLING_REMINDERS_CRON=0 * * * * *` (every minute) and restart the backend.
4. Watch Mailpit.

**For real email**, point the app at an SMTP server (your email provider,
Amazon SES, Brevo, SendGrid…) in `.env`:

```
SPRING_MAIL_HOST=smtp.example.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=...
SPRING_MAIL_PASSWORD=...
MAIL_FROM=ShortLink <no-reply@yourdomain.com>
```

## 5. Going live

1. Complete Razorpay's account activation (business details and KYC).
2. Switch the dashboard to **Live Mode** and generate **live** API keys
   (`rzp_live_…`). Set them as `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` on
   the production server.
3. Add a live-mode webhook pointing at your real domain
   (`https://<your-domain>/api/v1/billing/webhook`) with its own secret.
4. Configure a real SMTP server (section 4) and set `FRONTEND_URL` to the public
   site, so the **Pay now** links in emails point to it.
5. Turn off the development plan switch (`DEV_PLAN_SWITCH_ENABLED=false`).
