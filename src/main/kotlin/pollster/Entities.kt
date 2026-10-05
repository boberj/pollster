package pollster

import jetlin.db.Entity
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record

/**
 * Who Pollster is acting for: a browser, and what it has proven about itself.
 *
 * Pollster has no accounts. A browser is identified only by what it holds:
 *
 * - [names]: the name it picked on each poll, by poll slug. It comes from the sign-in cookie, so it
 *   lasts across visits. Votes are matched by name, so this is what lets a browser change "Alice's"
 *   votes on one poll.
 * - [adminTokens]: the admin tokens it has shown. It isn't stored anywhere. A page that receives a
 *   token in its URL adds it for its own writes (see [withAdminToken]), so knowing the admin link is
 *   exactly what grants the right to edit the poll, from any device.
 *
 * It isn't a stored record. `jetlin-db` only needs something that its policies can ask questions
 * about, and everything a voter is, is in the session.
 */
data class Voter(
    val names: Map<String, String> = emptyMap(),
    val adminTokens: Set<String> = emptySet(),
) : Principal {
    /** The name this browser picked on [poll], or `null` if it hasn't picked one yet. */
    fun nameOn(poll: Poll): String? = names[poll.slug]

    /** Whether this browser has shown [poll]'s admin token. */
    fun administers(poll: Poll): Boolean = poll.adminToken in adminTokens

    /** This voter, also holding [token]. Use it for writes made by a page that was given the token. */
    fun withAdminToken(token: String): Voter = copy(adminTokens = adminTokens + token)

    companion object {
        /** A browser that hasn't picked a name anywhere yet. */
        val Anonymous: Voter = Voter()
    }
}

/**
 * One poll: a question, and whether voters can pick one option or several.
 *
 * @property slug the memorable public ID in the voting link, such as `acorn-ballet-irony`.
 * @property adminToken the secret in the admin link. Anyone who has it can edit the poll.
 * @property createdAt when the poll was created, in milliseconds since the epoch.
 */
@Entity
class Poll(
    val slug: String,
    val adminToken: String,
    question: String,
    multiple: Boolean,
    val createdAt: Long,
) : Record() {
    var question: String by column(question)

    /** Whether a voter can vote for several options. If not, voting for one moves their vote there. */
    var multiple: Boolean by column(multiple)

    /**
     * Anyone can see a poll: knowing its slug is what the voting link is for. Only someone holding the
     * admin token can create or change one. Creating one counts, which is why the page that creates a
     * poll acts with the token it just generated.
     */
    companion object : Policy<Poll, Voter> {
        override fun canRead(record: Poll, principal: Voter): Boolean = true
        override fun canWrite(record: Poll, principal: Voter): Boolean = principal.administers(record)
    }
}

/**
 * One of a poll's options.
 *
 * @property position the option's place in the list, starting at 0.
 */
@Entity
class PollOption(
    val poll: Poll,
    text: String,
    position: Int,
) : Record() {
    var text: String by column(text)
    var position: Int by column(position)

    /** Visible to anyone. Added, changed, and removed by whoever administers the poll. */
    companion object : Policy<PollOption, Voter> {
        override fun canRead(record: PollOption, principal: Voter): Boolean = true
        override fun canWrite(record: PollOption, principal: Voter): Boolean = principal.administers(record.poll)
    }
}

/**
 * One voter's vote for one option.
 *
 * Votes are matched by name, case-insensitively: picking "alice" on a poll lets you change Alice's
 * votes there. [nameKey] holds the normalized form, so the comparison
 * can't be done inconsistently in two places.
 *
 * @property name the name as the voter typed it, for display.
 */
@Entity
class Vote(
    val option: PollOption,
    val name: String,
) : Record() {
    /** [name] normalized for comparison. */
    val nameKey: String get() = nameKey(name)

    /**
     * Anyone can see who voted for what: showing each voter's initial is part of the results.
     *
     * A browser can cast or withdraw a vote only under the name it picked on that poll. So an event
     * crafted by hand can't vote as someone the browser hasn't chosen to be. Whoever administers the
     * poll can also delete votes, which happens when they remove an option.
     */
    companion object : Policy<Vote, Voter> {
        override fun canRead(record: Vote, principal: Voter): Boolean = true

        override fun canWrite(record: Vote, principal: Voter): Boolean {
            val name = principal.nameOn(record.option.poll) ?: return false
            return nameKey(name) == record.nameKey
        }

        override fun canDelete(record: Vote, principal: Voter): Boolean =
            canWrite(record, principal) || principal.administers(record.option.poll)
    }
}

/** Normalizes a voter's name for comparison, so that "Alice " and "alice" are the same voter. */
fun nameKey(name: String): String = name.trim().lowercase()
