package pollster.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import jetlin.html.Div
import jetlin.html.Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * A short notice in the corner of the screen, such as "Poll updated!".
 *
 * @property destructive whether it reports a failure, which shows it in red.
 */
data class Toast(val id: Long, val title: String, val description: String?, val destructive: Boolean)

/**
 * The session's toasts.
 *
 * It lives in `app { }`, which is composed once per session, so a toast shown just before
 * navigating, such as "Poll updated!" on the way back to the poll, is still there after the
 * navigation.
 */
class Toaster {
    private var nextId = 0L

    /** The toasts on screen, oldest first. */
    val toasts = mutableStateListOf<Toast>()

    /** Shows a toast. It goes away by itself after a few seconds. */
    fun show(title: String, description: String? = null, destructive: Boolean = false) {
        toasts += Toast(nextId++, title, description, destructive)
        // One toast at a time. A queue of stale notices isn't useful.
        while (toasts.size > 1) toasts.removeAt(0)
    }

    fun dismiss(toast: Toast) {
        toasts.remove(toast)
    }
}

/** The session's [Toaster]. Read it with `LocalToaster.current`. */
val LocalToaster = staticCompositionLocalOf<Toaster> { error("No Toaster provided; ToastHost belongs in app { }") }

/**
 * Renders [toaster]'s toasts, and removes each one after [lifetime].
 *
 * The timer runs on the server, which is where the toast list is, so a toast disappears even if
 * the browser does nothing.
 */
@Composable
fun ToastHost(toaster: Toaster, lifetime: Duration = 4.seconds) {
    Div({
        classes("fixed bottom-0 right-0 z-50 flex w-full flex-col gap-2 p-4 sm:max-w-sm")
        attr("aria-live", "polite")
    }) {
        for (toast in toaster.toasts) {
            key(toast.id) {
                LaunchedEffect(toast.id) {
                    delay(lifetime)
                    toaster.dismiss(toast)
                }
                Div({
                    testTag("toast")
                    classes(
                        if (toast.destructive) {
                            "animate-toast-in rounded-md border border-destructive bg-destructive p-4 text-destructive-foreground shadow-lg"
                        } else {
                            "animate-toast-in rounded-md border bg-card p-4 text-card-foreground shadow-lg"
                        },
                    )
                    attr("role", "status")
                    onClick { toaster.dismiss(toast) }
                }) {
                    Div({ classes("text-sm font-semibold") }) { Text(toast.title) }
                    toast.description?.let { Div({ classes("text-sm opacity-90") }) { Text(it) } }
                }
            }
        }
    }
}
