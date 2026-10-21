package expo.modules.authtab

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import expo.modules.core.interfaces.Package
import expo.modules.core.interfaces.ReactActivityLifecycleListener

class AuthTabPackage : Package {
  override fun createReactActivityLifecycleListeners(activityContext: Context?): List<ReactActivityLifecycleListener> =
    listOf(AuthTabActivityListener())
}

private class AuthTabActivityListener : ReactActivityLifecycleListener {
  // Runs inside the activity's onCreate, the last point at which it may register a result launcher.
  override fun onCreate(activity: Activity, savedInstanceState: Bundle?) {
    (activity as? ComponentActivity)?.let(AuthTabLauncher::register)
  }

  override fun onDestroy(activity: Activity) {
    AuthTabLauncher.unregister(activity)
  }
}
