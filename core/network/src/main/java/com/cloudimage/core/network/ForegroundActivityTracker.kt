package com.cloudimage.core.network

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the activity the user is currently looking at — the one piece
 * of window state the Cloudflare solver needs.
 *
 * A challenge-solving WebView must be ATTACHED to a real window to settle:
 * Cloudflare's challenge scripts read `document.visibilityState`, and a
 * WebView that was never attached reports "hidden" — a signal bot
 * management treats as exactly what it is. The solver therefore shows its
 * WebView in a dialog over the resumed activity, and this tracker is how
 * it finds that activity from deep inside the network stack, where no
 * Activity could otherwise be known.
 *
 * Registered once from the application's `onCreate` via
 * [Application.registerActivityLifecycleCallbacks]; main-thread confined
 * callbacks write the single volatile field, and [current] reads it from
 * any thread. Pausing the last activity clears it — the app is backgrounded
 * and there is no window to attach to.
 */
@Singleton
class ForegroundActivityTracker
    @Inject
    constructor() : Application.ActivityLifecycleCallbacks {
        @Volatile
        private var resumed: Activity? = null

        /** The currently resumed activity, or null while the app is backgrounded. */
        fun current(): Activity? = resumed

        override fun onActivityResumed(activity: Activity) {
            resumed = activity
        }

        override fun onActivityPaused(activity: Activity) {
            // Only the resident clears itself: a pause of some OTHER activity
            // (a permission dialog's host round-trip, say) must not blank the
            // tracker while the app's own activity is still what's on screen.
            if (resumed === activity) {
                resumed = null
            }
        }

        // The rest of the lifecycle does not change who is on screen.

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
