# expo-auth-tab

Chrome's [Auth Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) for Expo apps on Android.

On Android, `expo-web-browser`'s `openAuthSessionAsync` (and so `expo-auth-session`'s `promptAsync`) runs a JavaScript polyfill:

- It opens a Chrome Custom Tab in a new task.
- It waits for whichever comes first: the app returning to the foreground (read as a dismiss) or the redirect arriving as a deep link.

As a result, every sign-in ends with a full app switch back through your launch screen. And if the foreground event wins the race, a sign-in that went through reads as cancelled.

Auth Tab is Android's answer to iOS's `ASWebAuthenticationSession`. The browser hands the redirect back to the activity that opened it, as an activity result. The app never leaves its task, and nothing races.

This module is a small native wrapper around `androidx.browser`'s `AuthTabIntent`. It has no opinion on OAuth: you build the URL and parse the result, for example with `expo-auth-session`, which keeps your PKCE and `state` handling unchanged.

> Android only. On iOS and web it always answers `unsupported`, so keep using `expo-auth-session` / `expo-web-browser` there.

## Requirements

- An Expo app with a native build (development build or `expo prebuild`); it does not run in Expo Go. Built and tested with Expo SDK 57 and React Native 0.86.
- `compileSdk` 36 and Android Gradle Plugin 8.9.1 or newer (required by `androidx.browser` 1.10).
- On the device, a default browser that supports Auth Tab: Chrome 137 or newer. Anything else answers `unsupported`, and you fall back to a Custom Tab (see below).
- For an `https` redirect, your app and the redirect host must be linked through [Digital Asset Links](https://developer.android.com/training/app-links/verify-android-applinks): `https://<host>/.well-known/assetlinks.json` must list your package name and signing-certificate fingerprint. Custom-scheme redirects need no verification.

## Install

```sh
npm install github:Musa-Ian/expo-auth-tab   # or: pnpm add / yarn add github:Musa-Ian/expo-auth-tab
npx expo prebuild   # or rebuild your development build
```

Autolinking picks up the module and the activity listener that registers its result launcher. There is nothing to add to `MainActivity`.

## API

```ts
import { openAuthTabAsync, type AuthTabResult } from 'expo-auth-tab';

const result: AuthTabResult = await openAuthTabAsync(authorizationUrl, redirectUrl);
```

`redirectUrl` is either `https://<host>/<path>`, which Chrome intercepts after verifying the host, or `<custom-scheme>:`, which Chrome intercepts by scheme. It resolves once the tab closes with one of these:

| `type` | meaning |
| --- | --- |
| `success` | The browser reached the redirect. `url` is the full redirect URL (query and fragment included). |
| `cancel` | The user closed the tab or pressed back. |
| `verification_failed` | Chrome could not verify, through Digital Asset Links, that your app owns the `https` redirect host. |
| `verification_timed_out` | Chrome could not fetch `assetlinks.json` in time. |
| `unsupported` | No Auth Tab was opened: the default browser has no Auth Tab, there is no activity to return to, the binary predates the module, or it is not Android. |
| `locked` | Another Auth Tab opened by this module is still open. |
| `unknown` | The browser returned an unrecognised result code. |

The promise never rejects for any of these outcomes. It rejects only for a malformed `redirectUrl`.

The tab always shares the browser's cookies: it is never ephemeral, so an identity provider the user is already signed in to in Chrome recognises them.

## With expo-auth-session

Build the URL from your `AuthRequest`, so `state` and the PKCE verifier are the request's own. Parse the redirect with the same request, and fall back to `promptAsync` on the same request when there is no Auth Tab:

```ts
import * as AuthSession from 'expo-auth-session';
import { openAuthTabAsync } from 'expo-auth-tab';
import { AppState, Platform } from 'react-native';

export async function authorize(
  request: AuthSession.AuthRequest,
  discovery: AuthSession.DiscoveryDocument,
): Promise<AuthSession.AuthSessionResult> {
  if (Platform.OS !== 'android') return request.promptAsync(discovery);
  const url = await request.makeAuthUrlAsync(discovery);
  const tab = await openAuthTabAsync(url, request.redirectUri);
  switch (tab.type) {
    case 'success':
      return tab.url ? request.parseReturnUrl(tab.url) : { type: 'cancel' };
    case 'cancel':
    case 'locked':
      return { type: tab.type };
    default:
      // unsupported, or the https redirect could not be verified: use a Custom Tab on the same request.
      await whenActive();
      return request.promptAsync(discovery);
  }
}

// The tab's result reaches JS before the activity resumes, so its AppState 'active' event is still to
// come. expo-web-browser's Custom Tab polyfill, opened before that event, would take it for its own tab
// closing and answer `dismiss` straight away, with the Custom Tab still open.
function whenActive(ceilingMs = 2000): Promise<void> {
  if (AppState.currentState === 'active') return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      sub.remove();
      resolve();
    };
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') done();
    });
    const timer = setTimeout(done, ceilingMs);
  });
}
```

Then exchange the code exactly as you would after `promptAsync`, with `request.codeVerifier`.

If you fall back to a Custom Tab with an `https` redirect, keep an intent filter for that path: the Custom Tab hands the redirect back as an App Link. Also make sure the router doesn't also open that link as a screen. In expo-router, `+native-intent.tsx` can drop it.

## How it works

- `AuthTabPackage` registers a `ReactActivityLifecycleListener`. Its `onCreate` registers `AuthTabIntent`'s `ActivityResultLauncher` on every activity, which has to happen before the activity is STARTED, so it cannot wait until JS asks.
- `openAsync` opens the URL in the default browser only if that browser declares Auth Tab support (`CustomTabsClient.isAuthTabSupported`). The intent is pinned to that browser's package.
- The activity result settles the pending promise.
- **If the activity is recreated while the tab is open** (a font-scale or locale change, "Don't keep activities", the system reclaiming it), React Native's `MainActivity` calls `super.onCreate(null)`. That loses the request code the tab was launched under, so the result can't be delivered. The pending flow is then answered `cancel` rather than left waiting for ever.
- If the process dies while the tab is open, the result is dropped. The JS that was waiting for it is gone too.

## Status

`0.1.0`, extracted from an Expo app in development. It has been checked on an Android emulator (Chrome 149): success, back, close, and the Custom Tab fallback. It is not on npm yet. Issues and pull requests are welcome.

## License

[MIT](LICENSE)
