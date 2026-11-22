# Setting up "Continue with Google" and "Continue with GitHub"

The app shows each sign-in button only when that provider's **client ID and
client secret** are set. You get them by registering the app with Google and
with GitHub. Each takes about 5 minutes, is free, and they are independent:
you can set up just one.

You will need, for local development:

| | Value |
|---|---|
| Frontend address | `http://localhost:4200` |
| Google callback URL | `http://localhost:8080/login/oauth2/code/google` |
| GitHub callback URL | `http://localhost:8080/login/oauth2/code/github` |

The callback URLs must match **exactly**: `http`, `localhost` (not
`127.0.0.1`), port `8080`, no trailing slash.

---

## 1. Google

1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and sign in.
2. **Create a project**: project picker at the top → **New project** → name it
   (e.g. `ShortLink local`) → **Create**, then make sure it is selected.
3. Open **APIs & Services → OAuth consent screen** (in newer consoles this is
   called **Google Auth Platform**). If asked, click **Get started**:
   - **App name**: `ShortLink`; **User support email**: your email.
   - **Audience**: **External**.
   - **Contact information**: your email. Accept the policy and **Create**.
4. Still there, open **Audience** and under **Test users** click **Add users**
   and add the Google accounts you will sign in with. While the app is in
   *Testing*, only these accounts can sign in.
5. Open **Clients** (or **APIs & Services → Credentials → Create credentials →
   OAuth client ID**):
   - **Application type**: **Web application**; **Name**: `ShortLink local`.
   - **Authorized redirect URIs** → **Add URI**:
     `http://localhost:8080/login/oauth2/code/google`
   - Leave *Authorized JavaScript origins* empty. **Create**.
6. Copy the **Client ID** and **Client secret** shown (you can see the secret
   again later under the client).

The app asks only for `openid`, `email` and `profile`, which Google does not
need to review.

## 2. GitHub

1. On GitHub: your avatar → **Settings → Developer settings → OAuth Apps →
   New OAuth App** (direct link: <https://github.com/settings/applications/new>).
2. Fill in:
   - **Application name**: `ShortLink local`
   - **Homepage URL**: `http://localhost:4200`
   - **Authorization callback URL**: `http://localhost:8080/login/oauth2/code/github`
   - Leave **Enable Device Flow** unticked. **Register application**.
3. Copy the **Client ID**. Click **Generate a new client secret** and copy it
   straight away: GitHub shows it only once.

The app asks for `read:user` and `user:email`. The second lets it read which
of the account's emails are **verified**, even when the user keeps their
email private.

## 3. Give the IDs to the app

Add them to `url_shortenerV4/.env` (never commit this file; it is in
`.gitignore`):

```properties
GOOGLE_CLIENT_ID=1234567890-abc...apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-...
GITHUB_CLIENT_ID=Ov23li...
GITHUB_CLIENT_SECRET=...
```

Then restart the backend so it reads them:

```bash
cd url_shortenerV4
docker compose up -d app
```

(If you run the backend from your IDE instead, set the same four variables in
its run configuration.)

## 4. Check it works

1. `curl http://localhost:8080/api/v1/auth/public/oauth/providers` should list
   the providers you set up, e.g. `"data":["google","github"]`.
2. Open <http://localhost:4200/login>: the **Continue with Google / GitHub**
   buttons appear above the username form.
3. Click one, sign in at Google/GitHub, and you land on your dashboard.

What happens to accounts:

- **First sign-in** creates a new account on the Free plan, with a username
  made from your email or GitHub login.
- If the provider confirms your email is **verified** and an account with that
  email already exists, you sign into **that** account instead, and the
  provider is linked to it.
- An **unverified** email is never used to link, so nobody can take over an
  account by putting its email on a Google/GitHub account.

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| Google: `Error 400: redirect_uri_mismatch` | The redirect URI in the Google client is not exactly `http://localhost:8080/login/oauth2/code/google`. Fix it there; changes can take a few minutes. |
| Google: "Access blocked: ShortLink has not completed the Google verification process" | That Google account is not a **test user** (step 1.4). Add it. |
| GitHub: "The redirect_uri is not associated with this application" | The GitHub app's callback URL is not exactly `http://localhost:8080/login/oauth2/code/github`. |
| No buttons on the login page | The backend did not get both the ID and the secret for that provider. Check `.env` and restart with `docker compose up -d app`; check step 4.1. |
| Back on the login page with "Signing in with that account did not work" | You cancelled at the provider, or it refused. The backend log has the reason (`docker compose logs app`). |
| "Sign-in is temporarily unavailable" | Redis is down: one-time sign-in codes are kept there. |

## Going live

When the app runs on a real domain:

1. Add the production callback URLs (with `https://` and your API's domain)
   to both the Google client and the GitHub app, e.g.
   `https://api.example.com/login/oauth2/code/google`.
2. Set `FRONTEND_URL` to the frontend's address so the backend sends users
   back to the right place.
3. In Google, **publish** the app (Audience → Publish app) so accounts other
   than test users can sign in.
4. Use separate clients and secrets for production; keep them out of git.
