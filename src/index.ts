import { requireOptionalNativeModule } from 'expo';
import { Platform } from 'react-native';

/**
 * How an Auth Tab ended. `success` carries the URL the browser was sent to; `unsupported` means none was
 * opened (no browser with Auth Tab, or no activity to return to) and the caller should use a Custom Tab;
 * `verification_*` means Chrome could not confirm, through Digital Asset Links, that the app owns an
 * https redirect's host.
 */
export type AuthTabResult =
  | { type: 'success'; url: string | null }
  | {
      type: 'cancel' | 'verification_failed' | 'verification_timed_out' | 'unsupported' | 'locked' | 'unknown';
      url: null;
    };

type Native = {
  openAsync(url: string, redirectUrl: string): Promise<AuthTabResult>;
};

// Android only, and optional: a binary built without the module answers `unsupported` rather than throwing.
const native = Platform.OS === 'android' ? requireOptionalNativeModule<Native>('ExpoAuthTab') : null;

/**
 * Opens `url` in Chrome's Auth Tab and resolves when it closes: on reaching `redirectUrl`
 * (`https://<host>/<path>`, verified against the host's assetlinks.json, or `<custom-scheme>:`), or on the
 * user closing it. The result comes back to the activity that opened it, so the app never leaves its task.
 */
export function openAuthTabAsync(url: string, redirectUrl: string): Promise<AuthTabResult> {
  return native ? native.openAsync(url, redirectUrl) : Promise.resolve({ type: 'unsupported', url: null });
}
