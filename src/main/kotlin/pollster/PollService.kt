package pollster

import jetlin.db.Db

/**
 * Everything Pollster does to stored polls: looking them up, creating and editing them, and voting.
 *
 * This is the only copy of the voting rules. A vote is an event handler on the server that writes
 * the database, and every page that shows the poll recomposes from the stored result. The browser
 * never predicts the outcome of a click, so there's no second copy of the rules there to keep in
 * step with this one.
 *
 * Every function takes the [Voter] from context, and `jetlin-db` checks each read and write against
 * the entities' policies. A function here can't do anything the voter isn't allowed to.
 */
class PollService(private val db: Db) {

    /** Returns the poll with this [slug], or `null` if there isn't one. */
    context(voter: Voter)
    fun bySlug(slug: String): Poll? = db.polls.firstOrNull { it.slug == slug }

    /**
     * Returns the poll with this [slug], but only if [token] is its admin token.
     *
     * The comparison takes the same time however much of the token matches, so response times
     * can't be used to guess it a character at a time.
     */
    context(voter: Voter)
    fun byAdminLink(slug: String, token: String): Poll? =
        bySlug(slug)?.takeIf { java.security.MessageDigest.isEqual(it.adminToken.toByteArray(), token.toByteArray()) }

    /** Returns [poll]'s options in their display order. */
    context(voter: Voter)
    fun options(poll: Poll): List<PollOption> = poll.pollOptions.sortedBy { it.position }

    /** Returns every vote on [poll], across all its options. */
    context(voter: Voter)
    fun votes(poll: Poll): List<Vote> = poll.pollOptions.flatMap { it.votes }

    /**
     * Returns the names that have voted on [poll], each once, in the order they first voted.
     *
     * Names that differ only in case or surrounding spaces are the same voter, so only the first
     * spelling is listed.
     */
    context(voter: Voter)
    fun voterNames(poll: Poll): List<String> =
        votes(poll).sortedBy { it.id }.distinctBy { it.nameKey }.map { it.name }

    /** The two links a new poll gets: [slug] for voting, and [adminToken] for editing. */
    data class Created(val slug: String, val adminToken: String)

    /**
     * Creates a poll and its options, and returns its links.
     *
     * Creating a poll needs its admin token, like any other change to it. The token is generated here,
     * so this function holds it, and it makes the writes as [voter] holding that token.
     *
     * It returns the links rather than the [Poll] record, because the record was obtained by a
     * different principal (the voter holding the token) than the page that called this.
     */
    context(voter: Voter)
    fun create(question: String, multiple: Boolean, options: List<String>): Created {
        val slug = generateSequence { Ids.slug() }.first { bySlug(it) == null }
        val token = Ids.adminToken()
        with(voter.withAdminToken(token)) {
            db.transact {
                val poll = db.polls.add(Poll(slug, token, question.trim(), multiple, System.currentTimeMillis()))
                options.forEachIndexed { index, text -> db.pollOptions.add(PollOption(poll, text.trim(), index)) }
            }
        }
        return Created(slug, token)
    }

    /**
     * One row of the edit form: an option that's already stored, or a new one.
     *
     * @property existing the stored option this row edits, or `null` for a new option.
     * @property text the option's text.
     */
    data class OptionEdit(val existing: PollOption?, val text: String)

    /**
     * Saves an edit of [poll]: its question, its type, and its options.
     *
     * Options are matched by record, not by text, so renaming an option keeps its votes. An option
     * that's no longer in [options] is deleted together with its votes.
     *
     * The writes are checked against the poll's policy, which needs the admin token. The admin page
     * calls this with a [Voter] that holds the token from its URL.
     */
    context(voter: Voter)
    fun update(poll: Poll, question: String, multiple: Boolean, options: List<OptionEdit>) {
        db.transact {
            poll.update {
                this.question = question.trim()
                this.multiple = multiple
            }
            val kept = options.mapNotNull { it.existing }.toSet()
            for (removed in poll.pollOptions.filter { it !in kept }) {
                removed.votes.forEach { it.delete() }
                removed.delete()
            }
            options.forEachIndexed { index, edit ->
                val text = edit.text.trim()
                val option = edit.existing
                if (option == null) {
                    db.pollOptions.add(PollOption(poll, text, index))
                } else if (option.text != text || option.position != index) {
                    option.update {
                        this.text = text
                        position = index
                    }
                }
            }
        }
    }

    /**
     * Votes for [option] under the name [voter] picked on its poll, or withdraws that vote.
     *
     * - Multiple choice: toggles the vote for this option only.
     * - Single choice: clicking the option you voted for withdraws the vote. Clicking another one
     *   moves your vote there.
     *
     * Does nothing if [voter] hasn't picked a name on the poll. The page doesn't offer voting then.
     */
    context(voter: Voter)
    fun toggleVote(option: PollOption) {
        val poll = option.poll
        val name = voter.nameOn(poll) ?: return
        val key = nameKey(name)
        db.transact {
            val mine = votes(poll).filter { it.nameKey == key }
            val here = mine.filter { it.option == option }
            when {
                here.isNotEmpty() -> here.forEach { it.delete() }
                poll.multiple -> db.votes.add(Vote(option, name.trim()))
                else -> {
                    mine.forEach { it.delete() }
                    db.votes.add(Vote(option, name.trim()))
                }
            }
        }
    }
}
