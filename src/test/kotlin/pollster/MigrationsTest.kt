package pollster

import java.nio.file.Path
import java.sql.DriverManager
import jetlin.db.Db
import jetlin.db.migrate.MigrationSource
import jetlin.db.migrate.migrate
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks Pollster's migrations, as the build packaged them into the classpath. Jetlin tests the
 * engine that applies them; these check what Pollster's own migrations do to its data.
 */
class MigrationsTest {

    @Test
    fun `the migrations are packaged`() {
        assertEquals(
            listOf("0001_initial_schema.sql", "0002_lock_links_after_first_visit.sql"),
            MigrationSource.Classpath.load().map { it.name },
        )
    }

    @Test
    fun `polls from before links locked stay locked`(): Unit = inTempDir { dir ->
        val file = dir.resolve("pollster.db")
        // A database from the version before 0002, with a poll whose link may already have been shared.
        migrate(file, MigrationSource.Classpath.load().take(1))
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO polls (adminToken, createdAt, multiple, question, slug) " +
                        "VALUES ('t', 0, 0, 'Lunch?', 'old-poll')",
                )
            }
        }

        // Starting the current version applies 0002.
        Db.open(file, JetlinSchema.tables).use { db ->
            with(Voter.Anonymous) { assertTrue(PollService(db).bySlug("old-poll")!!.visited) }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun inTempDir(block: (Path) -> Unit) {
        val dir = createTempDirectory("pollster-migrations")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
