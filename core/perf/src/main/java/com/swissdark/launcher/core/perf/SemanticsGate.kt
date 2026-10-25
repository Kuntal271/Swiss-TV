package com.swissdark.launcher.core.perf

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat

/**
 * Compose (1.7) builds and diffs the whole semantics tree after every frame whenever ANY
 * accessibility service is enabled. Our own "keep Home" service (HomeFallbackService, window
 * state events only) counts, and on the TV that work costs ~10 ms of main thread per frame
 * (checkForSemanticsChanges), which alone breaks the D-pad frame budget.
 *
 * When the only enabled services are this app's own, tell the Compose view's accessibility
 * delegate that none are enabled, so it skips the semantics work. As soon as any other service
 * (TalkBack, Switch Access, ...) is enabled, the delegate's own list is left untouched.
 *
 * The field is kept by core/perf's consumer ProGuard rules. Any failure leaves Compose as it is.
 */
object SemanticsGate {
    /** Call with the AndroidComposeView (LocalView.current). Returns a detach function. */
    fun attach(view: View): () -> Unit {
        val am = view.context.getSystemService(AccessibilityManager::class.java) ?: return NOOP
        val delegate = try { ViewCompat.getAccessibilityDelegate(view) } catch (_: Throwable) { null } ?: return NOOP
        val field = try {
            delegate.javaClass.getDeclaredField("enabledServices").apply { isAccessible = true }
        } catch (_: Throwable) {
            return NOOP
        }
        val own = view.context.packageName
        val apply = Runnable {
            try {
                val list = field.get(delegate) as? List<*> ?: return@Runnable
                if (list.isNotEmpty() && list.all { (it as? AccessibilityServiceInfo)?.resolveInfo?.serviceInfo?.packageName == own }) {
                    field.set(delegate, emptyList<AccessibilityServiceInfo>())
                }
            } catch (_: Throwable) {
            }
        }
        apply.run()
        // Compose refreshes its list from its own listeners; re-filter after them.
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { view.post(apply) }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { view.post(apply) }
        am.addAccessibilityStateChangeListener(stateListener)
        am.addTouchExplorationStateChangeListener(touchListener)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post(apply) }
            override fun onViewDetachedFromWindow(v: View) {}
        }
        view.addOnAttachStateChangeListener(attachListener)
        return {
            am.removeAccessibilityStateChangeListener(stateListener)
            am.removeTouchExplorationStateChangeListener(touchListener)
            view.removeOnAttachStateChangeListener(attachListener)
        }
    }

    private val NOOP: () -> Unit = {}
}
