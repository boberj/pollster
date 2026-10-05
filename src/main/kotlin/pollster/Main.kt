package pollster

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.auth.SessionAuthenticationScheme
import io.ktor.server.auth.SessionTransportType
import io.ktor.server.auth.install
import io.ktor.server.auth.session
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SameSite
import io.ktor.server.sessions.SessionStorage
import io.ktor.server.sessions.directorySessionStorage
import io.ktor.server.sessions.sameSite
import io.ktor.utils.io.ExperimentalKtorApi
import jetlin.db.Db
import jetlin.html.AttributeKey
import jetlin.html.pathParam
import jetlin.server.auth.authentication
import jetlin.server.jetlin
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlinx.serialization.Serializable

/**
 * Starts Pollster.
 *
 * Configuration comes from environment variables, each with a default for local use:
 *
 * - `PORT`: the port to listen on. Defaults to 8080.
 * - `POLLSTER_DATA`: the directory for the database and the sign-in sessions. Defaults to `data`.
 * - `POLLSTER_MIGRATIONS`: the directory of migration files to apply at startup. Defaults to
 *   `db/migrations`, which is right when running from the project directory. The Docker image sets it.
 * - `POLLSTER_TEST_TAGS`: set to `true` to write test tags into the HTML, for browser tests. Leave it
 *   off in production, where it would only add bytes to every page.
 */
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dataDir = Path(System.getenv("POLLSTER_DATA") ?: "data").createDirectories()
    val dbFile = dataDir.resolve("pollster.db")
    val applied = Migrations.apply(dbFile, Path(System.getenv("POLLSTER_MIGRATIONS") ?: "db/migrations"))
    if (applied.isNotEmpty()) println("[pollster] Applied migrations: ${applied.joinToString()}")
    val db = Db.open(dbFile, JetlinSchema.tables)
    val sessions = directorySessionStorage(dataDir.resolve("sessions").toFile())
    val exposeTestTags = System.getenv("POLLSTER_TEST_TAGS") == "true"

    embeddedServer(Netty, port = port) {
        pollster(db, sessions, exposeTestTags)
    }.start(wait = true)
}

/**
 * Installs Pollster into a Ktor application: its stylesheet, its sign-in sessions, and its pages.
 *
 * It's separate from [main] so that a test can start the same application on a test server.
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.pollster(db: Db, sessions: SessionStorage, exposeTestTags: Boolean = false) {
    val polls = PollService(db)

    routing {
        // Built by Tailwind and committed, so running the app needs no node.
        asset("app.css", ContentType.Text.CSS)
        asset("app.js", ContentType.Text.JavaScript)
        get("/favicon.ico") {
            val icon = checkNotNull(PollService::class.java.getResource("/pollster/favicon.ico")).readBytes()
            call.respondBytes(icon, ContentType.Image.XIcon)
        }
    }

    val sessionAuth = voterSessions(sessions)
    install(sessionAuth)

    jetlin {
        this.exposeTestTags = exposeTestTags
        head = """
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Lilita+One&family=Poppins:wght@400;600;700&display=swap" rel="stylesheet">
            <link rel="stylesheet" href="/pollster/app.css">
            <meta name="description" content="A simple and fun polling app.">
        """.trimIndent()
        clientSetup = """<script src="/pollster/app.js"></script>"""

        // Puts the voter from the sign-in cookie into every session, under VoterKey. Picking a name
        // on a poll is a sign-in, see NameEntry.
        val auth = authentication(sessionAuth, principal = VoterKey)

        // The header and the toasts are composed once per session, around whichever page is showing.
        app { route -> Shell(route) }

        view("/", title = "Pollster") { CreatePage(polls) }
        view("/poll/{slug}", title = "Pollster") { PollPage(polls, auth.rememberControls(), pathParam("slug")) }
        view("/poll/{slug}/admin/{token}", title = "Pollster") { AdminPage(polls, pathParam("slug"), pathParam("token")) }
    }
}

/** Where views read the [Voter] from. `jetlin-server-ktor-auth` fills it in from the sign-in cookie. */
val VoterKey: AttributeKey<Voter?> = AttributeKey("voter")

/**
 * What the sign-in cookie refers to: the name this browser picked on each poll, by poll slug.
 *
 * The cookie itself holds only an opaque ID. This object is stored on the server, in the session
 * storage. So the names can't be read from the cookie, and a leaked page can't be replayed from
 * another browser: `jetlin-server-ktor-auth` binds every page's session to this cookie.
 */
@Serializable
data class PollsterSession(val names: Map<String, String> = emptyMap())

/** The name of the sign-in cookie. */
const val SESSION_COOKIE: String = "pollster_session"

/**
 * The sign-in scheme: a cookie that holds a session ID, and the [Voter] that the session describes.
 *
 * A browser that has never picked a name has no session, and its pages see [Voter.Anonymous].
 */
@OptIn(ExperimentalKtorApi::class)
fun voterSessions(storage: SessionStorage): SessionAuthenticationScheme<PollsterSession, Voter> =
    session<PollsterSession, Voter>(SESSION_COOKIE) {
        transport = SessionTransportType.CookieId(storage) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.sameSite = SameSite.Lax
            // Remember the names for a year. Without a max age, the cookie would go away with the
            // browser, and a returning voter would have to pick their name again.
            cookie.maxAgeInSeconds = 365L * 24 * 60 * 60
        }
        validate { session -> Voter(names = session.names) }
    }

private fun io.ktor.server.routing.Routing.asset(name: String, type: ContentType) {
    get("/pollster/$name") {
        val text = checkNotNull(PollService::class.java.getResource("/pollster/$name")) {
            "/pollster/$name is missing from the resources" + if (name.endsWith(".css")) "; run `npm run build`" else ""
        }.readText()
        call.respondText(text, type)
    }
}
