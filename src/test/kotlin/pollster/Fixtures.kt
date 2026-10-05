package pollster

import jetlin.db.Db
import jetlin.server.auth.SessionControls
import jetlin.testing.ViewTest
import jetlin.testing.setRoutes
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/** Runs [block] against a new, empty database, so tests don't share state. */
@OptIn(ExperimentalPathApi::class)
fun withDb(block: (Db) -> Unit) {
    val directory = createTempDirectory("pollster-test")
    try {
        Db.open(directory.resolve("pollster.db"), JetlinSchema.tables).use(block)
    } finally {
        directory.deleteRecursively()
    }
}

/**
 * Records sign-ins and sign-outs instead of performing them.
 *
 * Signing in needs a real page load and a cookie, which a headless view test has neither of. What
 * Pollster decides is which names to sign in with and where to go next, and that's what this
 * records.
 */
class RecordingControls : SessionControls<PollsterSession> {
    /** Every call, in order, as `signIn(<names>, <next>)` or `signOut(<next>)`. */
    val calls: MutableList<String> = mutableListOf()

    override fun signIn(session: PollsterSession, next: String) {
        calls += "signIn(${session.names}, $next)"
    }

    override fun signOut(next: String) {
        calls += "signOut($next)"
    }
}

/** Sets up the application's routes, as [voter], the way `Main.kt` does. */
suspend fun ViewTest.pollster(polls: PollService, voter: Voter? = null, session: RecordingControls = RecordingControls()) {
    setAttribute(VoterKey, voter)
    setRoutes {
        app { route -> Shell(route) }
        view("/") { CreatePage(polls) }
        view("/poll/{slug}") { PollPage(polls, session, jetlin.html.pathParam("slug")) }
        view("/poll/{slug}/admin/{token}") { AdminPage(polls, jetlin.html.pathParam("slug"), jetlin.html.pathParam("token")) }
    }
}

/** Creates a poll as nobody in particular, the way the home page does, and returns its links. */
fun PollService.seed(question: String, vararg options: String, multiple: Boolean = false): PollService.Created =
    with(Voter.Anonymous) { create(question, multiple, options.toList()) }

/** Casts or withdraws [name]'s vote for the option with [text], as that voter would from their own browser. */
fun PollService.voteAs(name: String, slug: String, text: String) {
    with(Voter(names = mapOf(slug to name))) {
        val poll = checkNotNull(bySlug(slug))
        toggleVote(options(poll).first { it.text == text })
    }
}
