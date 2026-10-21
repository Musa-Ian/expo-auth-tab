package expo.modules.authtab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import java.lang.ref.WeakReference

/**
 * The current activity's Auth Tab result launcher, and the one flow waiting on it.
 *
 * A launcher must be registered before its activity is STARTED, so it cannot be made when JS asks for an
 * Auth Tab: AuthTabPackage registers one in every activity's onCreate. Main thread only.
 */
internal object AuthTabLauncher {
  private var launcher: ActivityResultLauncher<Intent>? = null
  private var owner: WeakReference<Activity>? = null
  private var waiting: ((resultCode: Int, url: Uri?) -> Unit)? = null

  fun register(activity: ComponentActivity) {
    // An activity recreated while the tab was open (a font-scale or locale change, "Don't keep activities",
    // memory pressure) never receives the result: React Native's MainActivity calls super.onCreate(null),
    // so the ActivityResultRegistry loses the request code it was launched under and drops it. Answer the
    // flow now, or it waits for ever and every later sign-in reads as locked.
    settle(AuthTabIntent.RESULT_CANCELED, null)
    launcher = AuthTabIntent.registerActivityResultLauncher(activity) { result ->
      // Nobody waiting: the process died with the tab open, and the flow that opened it went with it.
      settle(result.resultCode, result.resultUri)
    }
    owner = WeakReference(activity)
  }

  // Only the activity that registered: Expo runs onDestroy listeners in a coroutine, after the next
  // activity's onCreate, so a recreated activity's old instance must not clear its successor's launcher.
  fun unregister(activity: Activity) {
    if (owner?.get() === activity) {
      launcher = null
      owner = null
    }
  }

  /** Null unless `activity` is the live one that registered: a destroyed launcher throws on launch. */
  fun launcherFor(activity: Activity): ActivityResultLauncher<Intent>? =
    if (owner?.get() === activity && !activity.isFinishing && !activity.isDestroyed) launcher else null

  val busy: Boolean
    get() = waiting != null

  fun await(deliver: (resultCode: Int, url: Uri?) -> Unit) {
    waiting = deliver
  }

  fun abandon() {
    waiting = null
  }

  private fun settle(resultCode: Int, url: Uri?) {
    val deliver = waiting
    waiting = null
    deliver?.invoke(resultCode, url)
  }
}
