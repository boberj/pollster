package pollster

import java.security.SecureRandom
import java.util.Base64

/**
 * Generates the two kinds of identifier a poll has.
 *
 * The slug is meant to be read aloud and typed, so it's made of words. The admin token is meant to
 * be unguessable, so it's random bytes.
 */
object Ids {
    private val random = SecureRandom()

    /**
     * The words that slugs are made of: a diceware-style list of short, common words.
     *
     * Three of its ~1,950 words give about 7.4 billion slugs. That's plenty to avoid collisions
     * between polls, and [PollService.create] retries if one happens. It isn't meant to keep a poll
     * secret: the slug is the public voting link.
     */
    val words: List<String> by lazy {
        val stream = checkNotNull(Ids::class.java.getResourceAsStream("/pollster/wordlist.txt")) {
            "pollster/wordlist.txt is missing from the resources"
        }
        stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Returns a slug such as `acorn-ballet-irony`. */
    fun slug(words: Int = 3): String = List(words) { this.words[random.nextInt(this.words.size)] }.joinToString("-")

    /**
     * Returns a new admin token: 128 random bits, encoded so it can go in a URL.
     *
     * Anyone who has it can edit the poll, so it has to be impossible to guess, unlike the slug.
     */
    fun adminToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
