package pollster

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Brings the database up to date with the migrations in `db/migrations` when the app starts.
 *
 * `./gradlew dbMigrate` does the same during development, but a deployed container has no Gradle
 * and no source checkout. Without this, the first deployment after a schema change would fail to
 * start: `Db.open` refuses a database whose columns don't match the entities.
 *
 * It's deliberately compatible with `dbMigrate`. It records applied migrations in the same
 * `jetlin_migrations` table, by file name, so either one can be used on a database, and neither
 * applies a migration twice. Like `dbMigrate`, it refuses to apply a migration that destroys data
 * and still contains the `-- jetlin-db:unacknowledged` marker.
 *
 * It's simpler than `dbMigrate` in one way: it doesn't run that task's checks that a table rebuild
 * kept every index, trigger, and view. Pollster's database has none. Revisit this if that changes,
 * or better, once `jetlin-db` can apply migrations itself at runtime.
 */
object Migrations {
    private const val HISTORY = "jetlin_migrations"
    private const val UNACKNOWLEDGED = "-- jetlin-db:unacknowledged"

    /**
     * Applies every migration in [directory] that [database] hasn't had yet, in file name order, and
     * returns their names.
     *
     * Each migration runs in its own transaction. If one fails, it's rolled back, and the exception
     * stops the app from starting with a database it doesn't understand.
     */
    fun apply(database: Path, directory: Path): List<String> {
        check(directory.exists()) { "No migrations directory at $directory. Set POLLSTER_MIGRATIONS to db/migrations." }
        val migrations = directory.listDirectoryEntries("*.sql").filter { it.isRegularFile() }.sortedBy { it.name }

        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.execute("PRAGMA busy_timeout=5000")
            val fresh = !connection.hasTable(HISTORY)
            connection.execute("CREATE TABLE IF NOT EXISTS $HISTORY (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)")

            // A database created before this runner existed has its tables, made by Db.open from the
            // first schema, and no history. Running the first migration on it would fail with "table
            // already exists", so it's recorded as applied instead.
            if (fresh && connection.hasTable("polls")) migrations.firstOrNull()?.let { connection.record(it.name) }

            val applied = connection.appliedNames()
            val pending = migrations.filter { it.name !in applied }
            pending.firstOrNull { UNACKNOWLEDGED in it.readText() }?.let {
                error("${it.name} destroys data and hasn't been acknowledged. Read it, then delete its '$UNACKNOWLEDGED' line.")
            }
            for (migration in pending) connection.run(migration)
            return pending.map { it.name }
        }
    }

    private fun Connection.run(migration: Path) {
        autoCommit = false
        try {
            createStatement().use { statement ->
                for (sql in statements(migration.readText())) statement.execute(sql)
            }
            record(migration.name)
            commit()
        } catch (t: Throwable) {
            rollback()
            throw IllegalStateException("Migration ${migration.name} failed: ${t.message}", t)
        } finally {
            autoCommit = true
        }
    }

    private fun Connection.record(name: String) {
        prepareStatement("INSERT OR IGNORE INTO $HISTORY (name, applied_at) VALUES (?, datetime('now'))").use {
            it.setString(1, name)
            it.executeUpdate()
        }
    }

    private fun Connection.appliedNames(): Set<String> = createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM $HISTORY").use { rows ->
            buildSet { while (rows.next()) add(rows.getString(1)) }
        }
    }

    private fun Connection.hasTable(name: String): Boolean =
        prepareStatement("SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?").use {
            it.setString(1, name)
            it.executeQuery().use { rows -> rows.next() }
        }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    /**
     * Splits [sql] into statements at semicolons outside quotes, and drops `--` comments, the same
     * way `dbMigrate` does. JDBC runs one statement per call.
     */
    internal fun statements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < sql.length) {
            val character = sql[index]
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                }
                character == '\'' || character == '"' -> {
                    quote = character
                    current.append(character)
                }
                sql.startsWith("--", index) -> {
                    index = sql.indexOf('\n', index).let { if (it == -1) sql.length else it }
                    continue
                }
                character == ';' -> {
                    statements += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index++
        }
        statements += current.toString()
        return statements.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
