package pollster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Div
import jetlin.html.H2
import jetlin.html.P
import jetlin.html.Text
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import pollster.ui.ButtonLink
import pollster.ui.ButtonSize
import pollster.ui.ButtonVariant
import pollster.ui.Card
import pollster.ui.CardContent
import pollster.ui.CardDescription
import pollster.ui.CardHeader
import pollster.ui.CardTitle
import pollster.ui.Icon
import pollster.ui.LocalToaster
import pollster.ui.UiButton
import pollster.ui.UiIcon
import pollster.ui.UiInput
import pollster.ui.UiLabel

/**
 * The home page: a form that creates a poll, and then the poll's two links.
 *
 * The links replace the form in place. "Create Another" brings a fresh form back, without a page
 * load.
 */
@Composable
fun CreatePage(polls: PollService) = WithVoter {
    val toaster = LocalToaster.current
    var form by remember { mutableStateOf(PollFormState()) }
    var created by remember { mutableStateOf<PollService.Created?>(null) }

    Card("bg-card") {
        CardHeader {
            CardTitle("font-headline text-3xl") { Text("Create a New Poll") }
            CardDescription { Text("One decision, multiple choices. Let your friends decide!") }
        }
        CardContent {
            val links = created
            if (links != null) {
                PollLinks(
                    polls,
                    links,
                    onRenamed = { slug -> created = links.copy(slug = slug) },
                    onCreateAnother = {
                        form = PollFormState()
                        created = null
                    },
                )
            } else {
                PollForm(form, submitLabel = "Create Poll") {
                    try {
                        created = polls.create(form.question, form.multiple, form.filledRows.map { it.text })
                    } catch (e: Exception) {
                        // Rethrowing would end the handler before the toast is shown. Nothing was stored:
                        // a failed transaction leaves no trace.
                        System.err.println("[pollster] Creating a poll failed: $e")
                        toaster.show("Error creating poll", "There was a problem with your request.", destructive = true)
                    }
                }
            }
        }
    }
}

/**
 * What a new poll's creator sees: the voting link to share, the admin link to keep, and, until the
 * poll is first opened, a way to change the link.
 */
@Composable
context(voter: Voter)
private fun PollLinks(
    polls: PollService,
    links: PollService.Created,
    onRenamed: (String) -> Unit,
    onCreateAnother: () -> Unit,
) {
    val origin = siteOrigin()
    val voteUrl = "$origin/poll/${links.slug}"
    val adminUrl = "$origin/poll/${links.slug}/admin/${links.adminToken}"

    Div({ classes("animate-fade-in flex flex-col items-center space-y-6 text-center"); testTag("poll-links") }) {
        H2({ classes("font-headline text-3xl font-bold") }) { Text("🎉 Poll Created! 🎉") }
        P({ classes("max-w-md text-muted-foreground") }) {
            Text(
                "Your poll is live. Share the voting link with your friends and keep the admin link safe to manage " +
                    "your poll later.",
            )
        }
        Div({ classes("w-full max-w-md space-y-4 text-left") }) {
            CopyableLink("voting-link", "Voting Link", voteUrl, "Voting")
            CopyableLink("admin-link", "Admin Link", adminUrl, "Admin")
            // The poll was created with the token, so this page can act with it for the rename.
            with(voter.withAdminToken(links.adminToken)) {
                polls.byAdminLink(links.slug, links.adminToken)?.let { poll -> LinkEditor(polls, poll, onRenamed) }
            }
        }
        Div({ classes("flex w-full max-w-md flex-col gap-4 pt-4 sm:flex-row") }) {
            // A new tab, so the links stay on screen to copy after the creator has looked at the poll.
            ButtonLink(
                "/poll/${links.slug}",
                size = ButtonSize.Large,
                extraClasses = "flex-1",
                newTab = true,
                attrs = { testTag("go-to-poll") },
            ) {
                Text("Go to Poll")
                UiIcon(Icon.ExternalLink, "ml-2 h-4 w-4")
            }
            UiButton(ButtonVariant.Secondary, ButtonSize.Large, "flex-1", attrs = {
                testTag("create-another")
                onClick { onCreateAnother() }
            }) {
                Text("Create Another")
                UiIcon(Icon.Refresh, "ml-2 h-4 w-4")
            }
        }
    }
}

/**
 * A read-only field holding [url], with a button that copies it.
 *
 * Writing to the clipboard can only happen in the browser, so the button carries the text in a
 * `data-copy` attribute, and `app.js` copies it on click. The click also reaches the server, which
 * shows the toast and swaps the icon to a check mark for two seconds.
 */
@Composable
private fun CopyableLink(id: String, label: String, url: String, urlLabel: String) {
    val toaster = LocalToaster.current
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    copiedAt?.let { stamp ->
        LaunchedEffect(stamp) {
            delay(2.seconds)
            copiedAt = null
        }
    }

    Div({ classes("space-y-2") }) {
        UiLabel(forId = id) { Text(label) }
        Div({ classes("flex gap-2") }) {
            UiInput("bg-background") {
                id(id)
                testTag(id)
                attr("readonly", "")
                value(url)
            }
            UiButton(ButtonVariant.Outline, ButtonSize.Icon, attrs = {
                testTag("copy-$id")
                attr("aria-label", "Copy $urlLabel link")
                attr("data-copy", url)
                onClick {
                    toaster.show("Copied to clipboard!", "$urlLabel link copied.")
                    copiedAt = System.nanoTime()
                }
            }) {
                if (copiedAt != null) UiIcon(Icon.Check, "h-4 w-4 text-success") else UiIcon(Icon.Copy)
            }
        }
    }
}
