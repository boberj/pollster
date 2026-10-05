package pollster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import jetlin.html.Div
import jetlin.html.DocumentTitle
import jetlin.html.LocalNavigator
import jetlin.html.Text
import pollster.ui.ButtonLink
import pollster.ui.ButtonVariant
import pollster.ui.Card
import pollster.ui.CardContent
import pollster.ui.CardDescription
import pollster.ui.CardHeader
import pollster.ui.CardTitle
import pollster.ui.Icon
import pollster.ui.LocalToaster
import pollster.ui.UiIcon

/**
 * The admin page, reached through the secret link `/poll/{slug}/admin/{token}`.
 *
 * A wrong token shows the same page as a poll that doesn't exist, so a guess can't confirm that a
 * poll exists. The title is set only after the check, so `<head>` can't reveal the question either.
 *
 * The page acts as the current voter *holding the token from its URL*. That's what the policies on
 * [Poll] and [PollOption] check, so the token isn't only checked here: every write it makes is
 * checked against it again.
 */
@Composable
fun AdminPage(polls: PollService, slug: String, token: String) = WithVoter {
    with(contextOf<Voter>().withAdminToken(token)) {
        val poll = polls.byAdminLink(slug, token)
        if (poll == null) {
            PollNotFound()
        } else {
            AdminPanel(polls, poll)
        }
    }
}

@Composable
context(admin: Voter)
private fun AdminPanel(polls: PollService, poll: Poll) {
    DocumentTitle("Admin: ${poll.question} | Pollster")
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val form = remember(poll) {
        PollFormState(poll.question, poll.multiple, polls.options(poll).map { it to it.text })
    }

    Card {
        CardHeader {
            Div({ classes("flex items-start justify-between") }) {
                Div {
                    CardTitle("font-headline text-3xl") { Text("Admin Panel") }
                    CardDescription { Text("Edit your poll's question and options.") }
                }
                ButtonLink("/poll/${poll.slug}", ButtonVariant.Outline) {
                    Text("View Poll")
                    UiIcon(Icon.ExternalLink, "ml-2 h-4 w-4")
                }
            }
        }
        CardContent {
            PollForm(form, submitLabel = "Update Poll") {
                try {
                    polls.update(poll, form.question, form.multiple, form.filledRows.map { PollService.OptionEdit(it.existing, it.text) })
                    toaster.show("Poll Updated!", "Your poll has been successfully updated.")
                    navigator.push("/poll/${poll.slug}")
                } catch (e: Exception) {
                    System.err.println("[pollster] Updating poll ${poll.slug} failed: $e")
                    toaster.show("Error updating poll", "There was a problem with your request.", destructive = true)
                }
            }
        }
    }
}
