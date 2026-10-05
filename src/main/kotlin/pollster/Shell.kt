package pollster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Header
import jetlin.html.Link
import jetlin.html.LocalRequest
import jetlin.html.Text
import pollster.ui.ButtonLink
import pollster.ui.Card
import pollster.ui.CardContent
import pollster.ui.CardDescription
import pollster.ui.CardHeader
import pollster.ui.CardTitle
import pollster.ui.Icon
import pollster.ui.LocalToaster
import pollster.ui.ToastHost
import pollster.ui.Toaster
import pollster.ui.UiIcon

/**
 * The page chrome: the header, the page, and the toasts.
 *
 * It's composed once per session, around whichever page is current, so navigating between pages
 * doesn't re-send the header, and the session's [Toaster] outlives the page that showed a toast.
 */
@Composable
fun Shell(route: @Composable () -> Unit) {
    val toaster = remember { Toaster() }
    CompositionLocalProvider(LocalToaster provides toaster) {
        Div({ classes("flex min-h-screen flex-col") }) {
            Header({ classes("border-b bg-card px-4 py-4 sm:px-6 lg:px-8") }) {
                Div({ classes("container mx-auto flex items-center justify-between") }) {
                    Link("/", { classes("flex items-center gap-2") }) {
                        UiIcon(Icon.Vote, "h-8 w-8 text-primary")
                        H1({ classes("font-headline text-2xl font-bold tracking-tight text-foreground") }) {
                            Text("Pollster")
                        }
                    }
                }
            }
            jetlin.html.Element("main", { classes("flex-1 px-4 py-8 sm:px-6 lg:px-8") }) {
                Div({ classes("container mx-auto max-w-2xl") }) { route() }
            }
        }
        ToastHost(toaster)
    }
}

/**
 * Puts the current [Voter] in scope for [content], as a context parameter.
 *
 * Every database read and write needs a principal in scope, and context parameters don't pass
 * through a `@Composable () -> Unit`, so each page brings the voter back into scope at its root. A
 * browser that has never picked a name is [Voter.Anonymous].
 */
@Composable
fun WithVoter(content: @Composable context(Voter) () -> Unit) {
    val voter = LocalRequest.current[VoterKey] ?: Voter.Anonymous
    with(voter) { content() }
}

/**
 * Returns the site's origin, such as `https://pollster.example`, for links meant to be copied.
 *
 * It comes from the request that started the session. Behind a TLS-terminating proxy, the proxy is
 * expected to set `X-Forwarded-Proto`. `POLLSTER_ORIGIN` overrides both, for deployments where
 * neither header can be trusted.
 */
@Composable
fun siteOrigin(): String {
    System.getenv("POLLSTER_ORIGIN")?.let { return it.trimEnd('/') }
    val headers = LocalRequest.current.headers
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    val host = header("Host") ?: "localhost"
    val scheme = header("X-Forwarded-Proto") ?: "http"
    return "$scheme://$host"
}

/** The page for a poll that doesn't exist, or an admin link whose token is wrong. */
@Composable
fun PollNotFound() {
    jetlin.html.DocumentTitle("Poll Not Found | Pollster")
    Div({ classes("flex justify-center") }) {
        Card("w-full max-w-md text-center", { testTag("not-found") }) {
            CardHeader {
                Div({ classes("mx-auto w-fit rounded-full bg-secondary p-4") }) {
                    UiIcon(Icon.Frown, "h-16 w-16 text-primary")
                }
                CardTitle("mt-4 font-headline text-3xl") { Text("Poll Not Found") }
                CardDescription {
                    Text(
                        "We couldn't find the poll you were looking for. It might have been deleted or the link is incorrect.",
                    )
                }
            }
            CardContent { ButtonLink("/") { Text("Create a new poll") } }
        }
    }
}
