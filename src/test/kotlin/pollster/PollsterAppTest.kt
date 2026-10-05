package pollster

import jetlin.db.AccessDenied
import jetlin.testing.check
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
            onAll(hasText("Option text cannot be empty.")).assertCount(2)
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
    fun `"Not you" forgets only this poll's name`(): Unit = withDb { db ->
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
    fun `"Not you" signs out when it was the only name`(): Unit = withDb { db ->
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
