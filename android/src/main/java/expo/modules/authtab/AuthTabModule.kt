package expo.modules.authtab

import android.content.Context
import android.net.Uri
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class AuthTabModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("ExpoAuthTab")

    // Resolves once the Auth Tab closes, never rejects for an outcome of the flow: `unsupported` means no
    // Auth Tab was opened, and the caller should use a Custom Tab instead.
    AsyncFunction("openAsync") { url: String, redirectUrl: String, promise: Promise ->
      val redirect = Uri.parse(redirectUrl)
      val scheme = redirect.scheme?.lowercase()
      val host = redirect.host
      if (scheme == null || scheme == "http" || (scheme == "https" && host.isNullOrEmpty())) {
        promise.reject("ERR_AUTH_TAB_REDIRECT", "redirectUrl must be https://<host>/<path> or <custom-scheme>:", null)
        return@AsyncFunction
      }
      val browser = authTabBrowser(context)
      val launcher = appContext.currentActivity?.let(AuthTabLauncher::launcherFor)
      if (browser == null || launcher == null) {
        promise.resolve(outcome("unsupported"))
        return@AsyncFunction
      }
      if (AuthTabLauncher.busy) {
        promise.resolve(outcome("locked"))
        return@AsyncFunction
      }
      // Never ephemeral: the tab shares the browser's cookies, so an identity provider the user is already
      // signed in to there recognises them.
      val tab = AuthTabIntent.Builder().build()
      // The browser that was checked for Auth Tab, not whichever one the system would resolve the intent to.
      tab.intent.setPackage(browser)
      AuthTabLauncher.await { resultCode, resultUrl -> promise.resolve(outcome(resultCode, resultUrl)) }
      try {
        if (scheme == "https") {
          tab.launch(launcher, Uri.parse(url), host!!, redirect.path ?: "/")
        } else {
          tab.launch(launcher, Uri.parse(url), scheme)
        }
      } catch (e: Exception) {
        // Nothing was opened (the browser went away, the launcher was torn down): the caller uses a Custom Tab.
        AuthTabLauncher.abandon()
        promise.resolve(outcome("unsupported"))
      }
    }.runOnQueue(Queues.MAIN)

    // A JS reload: the promise waiting on the tab belongs to the runtime going away.
    OnDestroy { AuthTabLauncher.abandon() }
  }
}

/** The browser an Auth Tab would open in — the default one, when it offers Custom Tabs — or null if it has no Auth Tab. */
private fun authTabBrowser(context: Context): String? {
  val browser = CustomTabsClient.getPackageName(context, null) ?: return null
  return browser.takeIf { CustomTabsClient.isAuthTabSupported(context, it) }
}

private fun outcome(type: String, url: Uri? = null): Map<String, Any?> = mapOf("type" to type, "url" to url?.toString())

private fun outcome(resultCode: Int, resultUrl: Uri?): Map<String, Any?> = when (resultCode) {
  AuthTabIntent.RESULT_OK -> outcome("success", resultUrl)
  AuthTabIntent.RESULT_CANCELED -> outcome("cancel")
  AuthTabIntent.RESULT_VERIFICATION_FAILED -> outcome("verification_failed")
  AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> outcome("verification_timed_out")
  else -> outcome("unknown")
}
