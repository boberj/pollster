package pollster

import jetlin.db.AccessDenied
import jetlin.testing.check
import jetlin.testing.hasAttr
import jetlin.testing.click
import jetlin.testing.hasTestTag
import jetlin.testing.hasText
import jetlin.testing.recordUpdate
import jetlin.testing.runViewTest
import jetlin.testing.submit
import jetlin.testing.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests Pollster the way people use it: creating a poll, picking a name, voting, and editing.
 *
 * The tests describe what a user does and sees, through test tags, rather than the markup. Several
 * check something a browser test couldn't: which nodes a vote re-sent, or that a write refused by a
 * policy left nothing behind.
 */
class PollsterAppTest {

    @Test
    fun `submitting an empty form shows what's missing, and stores nothing`(): Unit = withDb { db ->
        val polls = PollService(db)
        runViewTest {
            pollster(polls)

            onNode(hasTestTag("poll-form")).submit()

            onNode(hasTestTag("question-error")).assertText("Please enter a question for your poll.")
            onNode(hasText("Please provide at least two options.")).assertExists()
            with(Voter.Anonymous) { assertTrue(db.polls.isEmpty()) }
        }
    }

    @Test
    fun `creating a poll shows its voting link and its secret admin link`(): Unit = withDb { db ->
        val polls = PollService(db)
        runViewTest {
            pollster(polls)

            onNode(hasTestTag("question")).type("Where should we eat?")
            onAll(hasTestTag("option"))[0].type("Pizza")
            onAll(hasTestTag("option"))[1].type("Sushi")
            onNode(hasTestTag("add-option")).click()
            onAll(hasTestTag("option"))[2].type("Tacos")
            onNode(hasTestTag("poll-form")).submit()

            onNode(hasTestTag("poll-links")).assertExists()
            val poll = with(Voter.Anonymous) { db.polls.single() }
            onNode(hasTestTag("voting-link")).assertValue("http://localhost/poll/${poll.slug}")
            onNode(hasTestTag("admin-link")).assertValue("http://localhost/poll/${poll.slug}/admin/${poll.adminToken}")
            with(Voter.Anonymous) {
                assertEquals("Where should we eat?", poll.question)
                assertEquals(listOf("Pizza", "Sushi", "Tacos"), polls.options(poll).map { it.text })
            }

            // "Create Another" brings back an empty form, without a page load.
            onNode(hasTestTag("create-another")).click()
            onNode(hasTestTag("question")).assertValue("")
        }
    }

    @Test
    fun `empty options are left out when the poll is created`(): Unit = withDb { db ->
        val polls = PollService(db)
        runViewTest {
            pollster(polls)

            onNode(hasTestTag("question")).type("Lunch?")
            onAll(hasTestTag("option"))[0].type("Yes")
            onNode(hasTestTag("add-option")).click()
            onNode(hasTestTag("add-option")).click()
            onAll(hasTestTag("option"))[2].type("No")
            onNode(hasTestTag("poll-form")).submit()

            onNode(hasTestTag("poll-links")).assertExists()
            with(Voter.Anonymous) {
                assertEquals(listOf("Yes", "No"), polls.options(db.polls.single()).map { it.text })
            }
        }
    }

    @Test
    fun `one filled option isn't enough, however many rows there are`(): Unit = withDb { db ->
        runViewTest {
            pollster(PollService(db))

            onNode(hasTestTag("question")).type("Lunch?")
            onNode(hasTestTag("add-option")).click()
            onAll(hasTestTag("option"))[1].type("Yes")
            onNode(hasTestTag("poll-form")).submit()

            onNode(hasText("Please provide at least two options.")).assertExists()
            onAll(hasTestTag("poll-links")).assertCount(0)
        }
    }

    @Test
    fun `the poll's link can be replaced right after creating it`(): Unit = withDb { db ->
        val polls = PollService(db)
        runViewTest {
            pollster(polls)
            onNode(hasTestTag("question")).type("Lunch?")
            onAll(hasTestTag("option"))[0].type("Yes")
            onAll(hasTestTag("option"))[1].type("No")
            onNode(hasTestTag("poll-form")).submit()

            onNode(hasTestTag("change-link")).click()
            onNode(hasTestTag("slug")).type("Team Lunch!")
            onNode(hasTestTag("slug-preview")).assertText("It will be saved as /poll/team-lunch")
            onNode(hasTestTag("link-form")).submit()

            onNode(hasTestTag("voting-link")).assertValue("http://localhost/poll/team-lunch")
            onNode(hasTestTag("go-to-poll")).assertMatches(hasAttr("href", "/poll/team-lunch"))
            with(Voter.Anonymous) { assertEquals("Lunch?", polls.bySlug("team-lunch")?.question) }
        }
    }

    @Test
    fun `a link that's taken or too short is refused, with a reason`(): Unit = withDb { db ->
        val polls = PollService(db)
        polls.seed("Taken?", "Yes", "No").let { taken ->
            with(Voter.Anonymous.withAdminToken(taken.adminToken)) { polls.rename(polls.bySlug(taken.slug)!!, "team-lunch") }
        }
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}/admin/${poll.adminToken}") {
            pollster(polls)

            onNode(hasTestTag("change-link")).click()
            onNode(hasTestTag("slug")).type("team-lunch")
            onNode(hasTestTag("link-form")).submit()
            onNode(hasTestTag("slug-error")).assertText("That link is taken. Try another.")

            onNode(hasTestTag("slug")).type("ab")
            onNode(hasTestTag("link-form")).submit()
            onNode(hasTestTag("slug-error")).assertText("A link needs at least 3 letters or digits.")
        }
        with(Voter.Anonymous) { assertTrue(polls.bySlug(poll.slug) != null) }
    }

    @Test
    fun `renaming on the admin page moves the admin page to the new link`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}/admin/${poll.adminToken}") {
            pollster(polls)

            onNode(hasTestTag("change-link")).click()
            onNode(hasTestTag("slug")).type("friday-lunch")
            onNode(hasTestTag("link-form")).submit()

            assertEquals("/poll/friday-lunch/admin/${poll.adminToken}", currentUrl)
            onNode(hasTestTag("poll-form")).assertExists()
        }
    }

    @Test
    fun `opening the voting page marks the poll as visited`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls)
            onNode(hasTestTag("question")).assertExists()
        }
        with(Voter.Anonymous) { assertTrue(polls.bySlug(poll.slug)!!.visited) }
    }

    @Test
    fun `once the poll is opened, its link is locked`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}/admin/${poll.adminToken}") {
            pollster(polls)
            onNode(hasTestTag("link-editor")).assertExists()

            // Someone opens the voting link in their own browser.
            with(Voter.Anonymous) { polls.markVisited(polls.bySlug(poll.slug)!!) }

            // The admin page, still open, stops offering the change.
            onAll(hasTestTag("link-editor")).assertCount(0)
        }
        with(Voter.Anonymous.withAdminToken(poll.adminToken)) {
            val stored = checkNotNull(polls.bySlug(poll.slug))
            assertTrue(stored.visited)
            assertEquals(
                PollService.Renamed.Refused("Someone has already opened this poll, so its link can't change."),
                polls.rename(stored, "too-late"),
            )
            // The policy refuses it too, so a stale page can't get around the check above.
            assertFailsWith<AccessDenied> { db.transact { stored.update { slug = "too-late" } } }
        }
        // Nobody can mark a poll unvisited, not even its admin.
        with(Voter.Anonymous.withAdminToken(poll.adminToken)) {
            assertFailsWith<AccessDenied> { db.transact { polls.bySlug(poll.slug)!!.update { visited = false } } }
        }
    }

    @Test
    fun `the buttons to the poll open it in a new tab`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}/admin/${poll.adminToken}") {
            pollster(polls)
            onNode(hasTestTag("view-poll")).assertMatches(hasAttr("target", "_blank"))
        }
    }

    @Test
    fun `a poll keeps at least two options`(): Unit = withDb { db ->
        runViewTest {
            pollster(PollService(db))

            onAll(hasTestTag("remove-option")).assertCount(0)
            onNode(hasTestTag("add-option")).click()
            onAll(hasTestTag("remove-option")).assertCount(3)
            onAll(hasTestTag("remove-option"))[0].click()
            onAll(hasTestTag("option")).assertCount(2)
            onAll(hasTestTag("remove-option")).assertCount(0)
        }
    }

    @Test
    fun `an unknown poll shows the not-found page`(): Unit = withDb { db ->
        runViewTest(url = "/poll/no-such-poll") {
            pollster(PollService(db))
            onNode(hasTestTag("not-found")).assertExists()
        }
    }

    @Test
    fun `a new voter enters a name, which signs them in on this poll only`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        val session = RecordingControls()
        runViewTest(url = "/poll/${poll.slug}") {
            // This browser already picked a name on another poll. That one has to survive.
            pollster(polls, Voter(names = mapOf("other-poll" to "Al")), session)

            onAll(hasTestTag("option")).assertCount(0)
            onNode(hasTestTag("name")).type("Alice")
            onNode(hasTestTag("name-form")).submit()

            assertEquals(listOf("signIn({other-poll=Al, ${poll.slug}=Alice}, /poll/${poll.slug})"), session.calls)
        }
    }

    @Test
    fun `everyone who voted is offered as a name to pick`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        polls.voteAs("Alice", poll.slug, "Yes")
        polls.voteAs("Bob", poll.slug, "No")
        polls.voteAs("alice", poll.slug, "Yes") // The same voter as "Alice", toggling the vote off...
        polls.voteAs("ALICE", poll.slug, "No") // ...and onto "No", so she's listed once.
        val session = RecordingControls()
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, session = session)

            onAll(hasTestTag("pick-name")).assertTexts("BBob", "AALICE")
            onNode(hasTestTag("pick-name") and hasText("Bob", substring = true)).click()

            assertEquals(listOf("signIn({${poll.slug}=Bob}, /poll/${poll.slug})"), session.calls)
        }
    }

    @Test
    fun `in a multiple-choice poll, each click toggles one option`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Toppings?", "Cheese", "Olives", "Ham", multiple = true)
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, Voter(names = mapOf(poll.slug to "Alice")))

            onAll(hasTestTag("option"))[0].click()
            onAll(hasTestTag("option"))[2].click()
            onAll(hasTestTag("count")).assertTexts("1 vote", "0 votes", "1 vote")
            onNode(hasTestTag("summary")).assertTextContains("Thanks for voting, Alice!")

            onAll(hasTestTag("option"))[0].click()
            onAll(hasTestTag("count")).assertTexts("0 votes", "0 votes", "1 vote")
        }
    }

    @Test
    fun `in a single-choice poll, voting moves the vote, and clicking it again withdraws it`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, Voter(names = mapOf(poll.slug to "Alice")))

            onAll(hasTestTag("option"))[0].click()
            onAll(hasTestTag("percent")).assertTexts("100%", "0%")
            onAll(hasTestTag("option"))[1].click()
            onAll(hasTestTag("count")).assertTexts("0 votes", "1 vote")
            onAll(hasTestTag("option"))[1].click()
            onAll(hasTestTag("count")).assertTexts("0 votes", "0 votes")
            onNode(hasTestTag("summary")).assertTextContains("Cast your vote below, Alice!")
        }
    }

    @Test
    fun `someone else's vote appears on an open page`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, Voter(names = mapOf(poll.slug to "Alice")))
            onAll(hasTestTag("count")).assertTexts("0 votes", "0 votes")

            // Bob votes from his own browser. Nothing here asks for an update.
            polls.voteAs("Bob", poll.slug, "No")

            onAll(hasTestTag("count")).assertTexts("0 votes", "1 vote")
            onAll(hasTestTag("voters"))[1].assertText("B")
        }
    }

    @Test
    fun `a vote re-sends only the option cards and the summary`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, Voter(names = mapOf(poll.slug to "Alice")))

            val update = recordUpdate { onAll(hasTestTag("option"))[0].click() }

            // Every card's percentage depends on the total, so both cards can change, but nothing
            // else on the page should: not the header, and not the question.
            update.assertOnlyWithin(hasTestTag("option"), hasTestTag("summary"))
        }
    }

    @Test
    fun `an admin link with the wrong token looks like a missing poll`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        runViewTest(url = "/poll/${poll.slug}/admin/not-the-token") {
            pollster(polls)
            onNode(hasTestTag("not-found")).assertExists()
            onAll(hasTestTag("poll-form")).assertCount(0)
        }
    }

    @Test
    fun `editing keeps the votes of options that stay, even renamed ones, and drops removed ones`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Pizza", "Sushi", "Tacos", multiple = true)
        polls.voteAs("Alice", poll.slug, "Sushi")
        polls.voteAs("Bob", poll.slug, "Tacos")
        runViewTest(url = "/poll/${poll.slug}/admin/${poll.adminToken}") {
            pollster(polls)

            onNode(hasTestTag("question")).type("Dinner?")
            onAll(hasTestTag("option"))[1].type("Sushi bar")
            onAll(hasTestTag("remove-option"))[2].click()
            onNode(hasTestTag("single")).check()
            onNode(hasTestTag("poll-form")).submit()

            assertEquals("/poll/${poll.slug}", currentUrl)
            onNode(hasTestTag("toast")).assertTextContains("Poll Updated!")
        }
        with(Voter.Anonymous) {
            val stored = checkNotNull(polls.bySlug(poll.slug))
            assertEquals("Dinner?", stored.question)
            assertEquals(false, stored.multiple)
            assertEquals(listOf("Pizza" to 0, "Sushi bar" to 1), polls.options(stored).map { it.text to it.votes.size })
            assertEquals(listOf("Alice"), polls.votes(stored).map { it.name })
        }
    }

    @Test
    fun `clearing an option's text on the admin page removes it`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Pizza", "Sushi", "Tacos")
        runViewTest(url = "/poll/${poll.slug}/admin/${poll.adminToken}") {
            pollster(polls)

            onAll(hasTestTag("option"))[1].type("")
            onNode(hasTestTag("add-option")).click()
            onNode(hasTestTag("poll-form")).submit()

            assertEquals("/poll/${poll.slug}", currentUrl)
        }
        with(Voter.Anonymous) {
            assertEquals(listOf("Pizza", "Tacos"), polls.options(checkNotNull(polls.bySlug(poll.slug))).map { it.text })
        }
    }

    @Test
    fun `only the admin token lets anyone edit a poll`(): Unit = withDb { db ->
        val polls = PollService(db)
        val created = polls.seed("Lunch?", "Yes", "No")
        val stranger = Voter(names = mapOf(created.slug to "Mallory"))

        with(stranger) {
            val poll = checkNotNull(polls.bySlug(created.slug))
            assertFailsWith<AccessDenied> { polls.update(poll, "Hijacked", false, emptyList()) }
            assertEquals("Lunch?", poll.question)
        }
        with(stranger.withAdminToken(created.adminToken)) {
            val poll = checkNotNull(polls.bySlug(created.slug))
            polls.update(poll, "Lunch today?", false, polls.options(poll).map { PollService.OptionEdit(it, it.text) })
            assertEquals("Lunch today?", poll.question)
        }
    }

    @Test
    fun `a browser can only vote under the name it picked`(): Unit = withDb { db ->
        val polls = PollService(db)
        val created = polls.seed("Lunch?", "Yes", "No")
        polls.voteAs("Alice", created.slug, "Yes")

        // Mallory picked her own name, then tries to store a vote as Alice, and to remove Alice's.
        with(Voter(names = mapOf(created.slug to "Mallory"))) {
            val poll = checkNotNull(polls.bySlug(created.slug))
            val yes = polls.options(poll).first()
            assertFailsWith<AccessDenied> { db.transact { db.votes.add(Vote(yes, "Alice")) } }
            assertFailsWith<AccessDenied> { db.transact { yes.votes.single().delete() } }
            assertEquals(listOf("Alice"), polls.votes(poll).map { it.name })
        }
        // A browser that hasn't picked a name on this poll can't vote at all.
        with(Voter.Anonymous) {
            val poll = checkNotNull(polls.bySlug(created.slug))
            assertFailsWith<AccessDenied> { db.transact { db.votes.add(Vote(polls.options(poll).last(), "Anyone")) } }
        }
    }

    @Test
    fun `the not-you link forgets only this poll's name`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        val session = RecordingControls()
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, Voter(names = mapOf(poll.slug to "Alice", "other-poll" to "Al")), session)

            onNode(hasTestTag("not-you")).click()

            assertEquals(listOf("signIn({other-poll=Al}, /poll/${poll.slug})"), session.calls)
        }
    }

    @Test
    fun `the not-you link signs out when it was the only name`(): Unit = withDb { db ->
        val polls = PollService(db)
        val poll = polls.seed("Lunch?", "Yes", "No")
        val session = RecordingControls()
        runViewTest(url = "/poll/${poll.slug}") {
            pollster(polls, Voter(names = mapOf(poll.slug to "Alice")), session)

            onNode(hasTestTag("not-you")).click()

            assertEquals(listOf("signOut(/poll/${poll.slug})"), session.calls)
        }
    }
}
