package pollster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.DocumentTitle
import jetlin.html.Form
import jetlin.html.H3
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.bind
import jetlin.html.rememberField
import jetlin.server.auth.SessionControls
import kotlin.math.roundToInt
import pollster.ui.ButtonVariant
import pollster.ui.Card
import pollster.ui.CardContent
import pollster.ui.CardDescription
import pollster.ui.CardHeader
import pollster.ui.CardTitle
import pollster.ui.FieldError
import pollster.ui.Icon
import pollster.ui.Progress
import pollster.ui.UiButton
import pollster.ui.UiIcon
import pollster.ui.UiInput
import pollster.ui.VoterAvatar

/**
 * The voting page.
 *
 * A browser that hasn't picked a name on this poll picks one first. After that, it sees the options
 * as cards it can click to vote. Every page showing this poll reads the same stored votes, so a vote
 * cast in one browser appears in all of them without any listener or refresh.
 *
 * @param session signs the browser in under the name it picks.
 */
@Composable
fun PollPage(polls: PollService, session: SessionControls<PollsterSession>, slug: String) = WithVoter {
    val poll = polls.bySlug(slug)
    if (poll == null) {
        PollNotFound()
        return@WithVoter
    }
    DocumentTitle("${poll.question} | Pollster")

    val voter = contextOf<Voter>()
    val name = voter.nameOn(poll)
    if (name == null) {
        NameEntry(polls, poll, session)
    } else {
        PollResults(polls, poll, name, session)
    }
}

/** Signs the browser in as [name] on [poll], keeping the names it picked on other polls. */
context(voter: Voter)
private fun SessionControls<PollsterSession>.pickName(poll: Poll, name: String) {
    signIn(PollsterSession(voter.names + (poll.slug to name.trim())), next = "/poll/${poll.slug}")
}

/**
 * Forgets the name the browser picked on [poll], so it can pick another.
 *
 * Its names on other polls stay. Only when there are none left does it sign out completely.
 */
context(voter: Voter)
private fun SessionControls<PollsterSession>.forgetName(poll: Poll) {
    val remaining = voter.names - poll.slug
    if (remaining.isEmpty()) {
        signOut(next = "/poll/${poll.slug}")
    } else {
        signIn(PollsterSession(remaining), next = "/poll/${poll.slug}")
    }
}

/**
 * Asks who's voting: a list of everyone who has voted on this poll, and a field for a new name.
 *
 * Votes are matched by name, so picking a name from the list is the same as typing it. Either way,
 * the browser signs in as that name on this poll. Signing in sets a cookie, which a WebSocket can't
 * do, so it costs one page load. It happens once per poll per browser.
 */
@Composable
context(voter: Voter)
private fun NameEntry(polls: PollService, poll: Poll, session: SessionControls<PollsterSession>) {
    val totalVotes = polls.votes(poll).size
    val names = polls.voterNames(poll)
    val newName = rememberField("") { if (it.isBlank()) "Please enter your name." else null }

    Card {
        CardHeader("text-center") {
            CardTitle("font-headline text-3xl lg:text-4xl", { testTag("question") }) { Text(poll.question) }
            CardDescription {
                Text("${votesLabel(totalVotes)} total. ")
                Text(if (names.isEmpty()) "Enter your name to cast your vote!" else "Who's voting?")
            }
        }
        CardContent("space-y-6") {
            if (names.isNotEmpty()) {
                Div({ classes("mx-auto max-w-sm space-y-2"); testTag("known-names") }) {
                    P({ classes("text-sm font-medium text-muted-foreground") }) { Text("I'm one of these:") }
                    Div({ classes("flex flex-wrap gap-2") }) {
                        for (known in names) {
                            key(nameKey(known)) {
                                UiButton(ButtonVariant.Outline, extraClasses = "rounded-full", attrs = {
                                    testTag("pick-name")
                                    onClick { session.pickName(poll, known) }
                                }) {
                                    VoterAvatar(known)
                                    Text(known)
                                }
                            }
                        }
                    }
                    P({ classes("pt-2 text-sm font-medium text-muted-foreground") }) { Text("Or I'm someone new:") }
                }
            }
            Form({
                classes("mx-auto flex max-w-sm items-start gap-2")
                testTag("name-form")
                onSubmit { values ->
                    // The submitted value, in case Enter beat the debounced input event.
                    newName.edit(values["name"] ?: newName.value)
                    if (newName.isValid) session.pickName(poll, newName.value)
                }
            }) {
                Div({ classes("flex-1 space-y-1") }) {
                    UiInput {
                        name("name")
                        testTag("name")
                        placeholder("Your Name")
                        attr("aria-label", "Your Name")
                        bind(newName)
                    }
                    newName.error?.let { FieldError(it) }
                }
                Button({
                    type("submit")
                    testTag("continue")
                    classes(pollster.ui.buttonClasses())
                }) { Text("Continue →") }
            }
        }
    }
}

/** The poll's options as cards, with their results. Clicking a card votes for it, or withdraws the vote. */
@Composable
context(voter: Voter)
private fun PollResults(polls: PollService, poll: Poll, name: String, session: SessionControls<PollsterSession>) {
    val options = polls.options(poll)
    val totalVotes = options.sumOf { it.votes.size }
    val me = nameKey(name)
    val hasVoted = options.any { option -> option.votes.any { it.nameKey == me } }

    Card("animate-fade-in w-full bg-card") {
        CardHeader("text-center") {
            CardTitle("font-headline text-3xl lg:text-4xl", { testTag("question") }) { Text(poll.question) }
            CardDescription({ testTag("summary") }) {
                Text("${votesLabel(totalVotes)} total.")
                if (poll.multiple) Text(" You can select multiple options.")
                Text(if (hasVoted) " Thanks for voting, $name!" else " Cast your vote below, $name!")
            }
            Div({ classes("pt-1 text-xs text-muted-foreground") }) {
                Button({
                    type("button")
                    testTag("not-you")
                    classes("inline-flex items-center gap-1 underline-offset-4 hover:underline")
                    onClick { session.forgetName(poll) }
                }) {
                    UiIcon(Icon.UserSwitch, "h-3 w-3")
                    Text("Not $name?")
                }
            }
        }
        CardContent("grid grid-cols-1 gap-4 md:grid-cols-2") {
            for (option in options) {
                key(option.id) { OptionCard(polls, option, totalVotes, me) }
            }
        }
    }
}

/** One option: its text, share of the votes, voters, and a bar. The whole card is the vote button. */
@Composable
context(voter: Voter)
private fun OptionCard(polls: PollService, option: PollOption, totalVotes: Int, me: String) {
    val votes = option.votes.sortedBy { it.id }
    val mine = votes.any { it.nameKey == me }
    val percent = if (totalVotes > 0) (votes.size * 100.0 / totalVotes).roundToInt() else 0

    Button({
        type("button")
        testTag("option")
        attr("aria-pressed", mine.toString())
        classes(
            if (mine) {
                "relative flex h-full w-full cursor-pointer flex-col items-stretch justify-between rounded-md border border-primary bg-primary/5 p-3 text-left shadow-lg transition-all"
            } else {
                "relative flex h-full w-full cursor-pointer flex-col items-stretch justify-between rounded-md border border-border p-3 text-left transition-all hover:border-primary/50"
            },
        )
        onClick { polls.toggleVote(option) }
    }) {
        Div({ classes("flex items-start justify-between") }) {
            H3({ classes("text-base font-semibold"); testTag("option-text") }) { Text(option.text) }
            Span({ classes("text-base font-bold"); testTag("percent") }) { Text("$percent%") }
        }
        Div({ classes("flex-1") })
        Div({ classes("space-y-2") }) {
            Div({ classes("flex min-h-[28px] flex-wrap items-center gap-1"); testTag("voters") }) {
                for (vote in votes) key(vote.id) { VoterAvatar(vote.name) }
            }
            Progress(percent, "h-2")
            Div({ classes("flex items-center justify-between text-sm text-muted-foreground") }) {
                Span({ testTag("count") }) { Text(votesLabel(votes.size)) }
            }
        }
    }
}

/** "1 vote", "2 votes". */
fun votesLabel(count: Int): String = if (count == 1) "1 vote" else "$count votes"
