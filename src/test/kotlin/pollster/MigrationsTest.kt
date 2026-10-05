package pollster

import java.nio.file.Path
import java.sql.DriverManager
import jetlin.db.Db
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks that the migrations in db/migrations bring any database Pollster may meet up to date. */
class MigrationsTest {
    private val migrations = Path("db/migrations")

    @Test
    fun `a new database gets every migration, and opens`(): Unit = inTempDir { dir ->
        val file = dir.resolve("pollster.db")

        val applied = Migrations.apply(file, migrations)

        assertEquals(listOf("0001_initial_schema.sql", "0002_lock_links_after_first_visit.sql"), applied)
        Db.open(file, JetlinSchema.tables).close()
        assertEquals(emptyList(), Migrations.apply(file, migrations))
    }

    @Test
    fun `a database from before migrations ran at startup is caught up, and its polls stay locked`(): Unit = inTempDir { dir ->
        val file = dir.resolve("pollster.db")
        // What Db.open made before this runner existed: the first schema, with no migration history.
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in Migrations.statements(migrations.resolve("0001_initial_schema.sql").readText())) {
                    statement.execute(sql)
                }
                statement.execute("INSERT INTO polls (adminToken, createdAt, multiple, question, slug) VALUES ('t', 0, 0, 'Lunch?', 'old-poll')")
            }
        }

        assertEquals(listOf("0002_lock_links_after_first_visit.sql"), Migrations.apply(file, migrations))

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
