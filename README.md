# MACS STOP Dialer 2 (Android)

SIP softphone for Android with the MACS STOP design. Calls use UDP, TCP or TLS (like MicroSIP) through the Linphone SDK.

What's in version 2:
- Admin login on the login screen (user `jin`, password `000555`) with a dashboard: add companies (company name + caller number + SIP details), see live agents, call history, **Barge** and **Replace**.
- Agents only type the **company name + caller number** and tap **Check**; the SIP details arrive from the admin and update live.
- Manual SIP settings are hidden behind PIN `000666`.
- Call recording (saved in the app, play / share / export), CSV export of the call history.
- Caller area (city/state from the US area code), shown on the call screen and in history.
- Profile photo, Light / Dark / System theme, hold audio settings.
- In-app update check (GitHub Releases).
- Fixed: un-register now really signs the phone out of the PBX, and registrations expire after 2 minutes.

What's new in 2.x (this update):
- Keypad numbers sit in the middle of the screen.
- Incoming/outgoing call screen shows the caller's area code, state, main city, main ZIP and their local time (live clock). Works offline (`assets/www/areacodes.js`).
- Ringtone picker in Settings → Calls → Ringtone (7 built-in tones, the phone's own ringtones, or your own audio file) and a "Vibrate when ringing" switch. The ringtone now plays on the phone's ringer channel, so it sounds clean.
- **SIP 2 (outgoing line)**: Settings → Outgoing line (SIP 2), PIN `000666`. A separate SIP account just for outgoing calls; incoming calls stay on SIP 1. Short extensions (6 digits or fewer) and transfers stay on SIP 1.

---

## 1. Create the Firebase database (one time, ~5 minutes)

The admin dashboard and live sync need a free Firebase project.

1. Open **console.firebase.google.com** and sign in with a Google account.
2. **Create a project** → name it `macs-dialer` → you can turn Google Analytics off → **Create project**.
3. Left menu **Build → Firestore Database** → **Create database** → pick a location (for example `nam5 (United States)`) → **Start in production mode** → **Create**.
4. Open the **Rules** tab, replace everything with the rules below, and press **Publish**:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if true;
    }
  }
}
```

5. Click the **gear ⚙ → Project settings**. Under **Your apps** click the **`</>`** (Web) icon → nickname `dialer` → **Register app**.
6. You'll see a block like `const firebaseConfig = { apiKey: "...", ... }`. Keep this page open.
7. In GitHub open `app/src/main/assets/www/firebase-config.js` → pencil ✏️ → fill in the six values (apiKey, authDomain, projectId, storageBucket, messagingSenderId, appId) → **Commit changes**.

> These rules let anyone who has the app read and write the data. That is fine for a private team app;
> before sharing the app widely, ask for the rules to be tightened (Firebase Authentication).

## 2. Build

Every commit builds a new APK automatically (**Actions** tab, about 3–5 minutes).
Each build is also published under **Releases** (right side of the repo page) as `MACS-STOP-Dialer.apk`,
which can be downloaded on a phone **without logging in to GitHub**.

The first time you install version 2, **uninstall version 1 first** (version 2 uses a new permanent signing key,
so later updates install on top without uninstalling).

## 3. Use

**Admin:** login screen → *Admin login* → `jin` / `000555` → **Add company** → fill in company name, caller number,
SIP details and the **admin number** (the phone/extension that rings when you Barge or Replace).

**Agent:** log in → Settings → *Company line* → type the same company name and caller number → **Check**.

**Barge** calls the admin number and joins it into the agent's call (3-way).
**Replace** transfers the call from the agent to the admin number.
Both need the admin number to be a different extension/phone than the agent's, and the PBX must allow transfers.

## Note

The Linphone SDK is licensed under GPLv3. Using the app for yourself and your team is fine; selling or publishing
the app requires a commercial Linphone license.
