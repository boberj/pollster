# Pollster

Pollster is a small polling app. You create a poll with a question and some options, share the
voting link, and everyone votes under their name. A secret admin link lets whoever created the poll
edit it later.

It's built with [Jetlin](https://github.com/boberj/jetlin), which runs the UI as Kotlin
`@Composable` functions on the server. The browser receives HTML, plus a small runtime that applies
the changes the server sends. There's no client-side state, no API in between, and only a few lines
of JavaScript, which copy links to the clipboard. When someone votes, every open page that shows the
poll updates, because each one reads the same stored votes.

## Run it

Jetlin isn't published to Maven yet, so Pollster builds it from source. Check out both repositories
side by side:

```
some-directory/
├── jetlin/
└── pollster/
```

Then, from `pollster/`:

```bash
./gradlew run      # http://localhost:8080
./gradlew build    # compiles, runs the tests, and checks the database schema
```

Gradle downloads a JDK 24 if you don't have one, because Jetlin compiles for Java 24.

The app keeps its data in `data/`: the SQLite database, and the sign-in sessions that remember each
browser's names. These environment variables change its behavior:

| Variable | Default | What it does |
|---|---|---|
| `PORT` | `8080` | The port to listen on. |
| `POLLSTER_DATA` | `data` | Where the database and sessions are stored. |
| `POLLSTER_ORIGIN` | from the request | The origin used in copyable links, such as `https://polls.example`. Set it when a proxy in front of the app doesn't send `X-Forwarded-Proto`. |
| `POLLSTER_MIGRATIONS` | `db/migrations` | The migrations to apply at startup. The default is right when running from the project directory. The Docker image sets it. |
| `POLLSTER_TEST_TAGS` | off | Set it to `true` to write test tags into the HTML, for browser tests. |

## Deploy it

The `Dockerfile` builds an image that serves Pollster on port 8080 and keeps its data in `/data`.
Jetlin isn't published yet, so the build fetches Jetlin's source from
[GitHub](https://github.com/boberj/jetlin) and compiles it alongside Pollster. The build needs no
other checkout, only network access to GitHub, Maven Central, and the Gradle distribution server.

### With Dokploy

[Dokploy](https://dokploy.com) builds the image from this repository and runs it behind its own
reverse proxy, which also gets the HTTPS certificate.

1. In a project, create an **Application**.
2. Under **Provider**, choose GitHub (or Git with the repository's URL), select this repository, and
   select the `main` branch.
3. Under **Build Type**, choose **Dockerfile**. The defaults are right: the Dockerfile is at
   `Dockerfile`, and the build context is the repository root.
4. Under **Advanced** > **Volumes**, add a volume mount with the mount path `/data`, and a name such as
   `pollster-data`. This is where the database and the sign-in sessions live. Without it, every
   deployment starts with no polls and signs everyone out.
5. Under **Domains**, add your domain with container port `8080`, and turn on HTTPS.
6. Click **Deploy**. The first build takes several minutes, because it compiles Jetlin and Pollster
   from source.

To redeploy on every push to `main`, turn on **Autodeploy** for the application.

Keep the application at one replica. Pollster stores everything in one SQLite file, and Jetlin keeps
each open page's state in the process's memory, so two containers can't share the work. A
deployment replaces the container, so open pages reload once, and nobody's data is lost.

**Settings you don't need to change.** Dokploy's proxy passes on the request's `Host` header, which
Jetlin needs to accept the page's WebSocket connection, and its `X-Forwarded-Proto` header, so the
links that Pollster offers to copy start with `https://`. If they start with `http://` instead, set
the environment variable `POLLSTER_ORIGIN` to your site's address, such as `https://polls.example`.

**Choosing the Jetlin version.** The image builds against Jetlin's `main` branch by default, so a
rebuild can pick up Jetlin changes you didn't intend to deploy. To pin a version, add a build-time
argument in the build arguments field of the application's **Environment** tab:

```
JETLIN_REF=574e334
```

It takes a commit SHA, a tag, or a branch name.

### With Docker alone

```bash
docker build -t pollster .
docker run -d -p 8080:8080 -v pollster-data:/data pollster
```

Pass `--build-arg JETLIN_REF=<sha>` to `docker build` to pin the Jetlin version.

## How it's organized

| File | What it holds |
|---|---|
| `Main.kt` | The server: the stylesheet routes, the sign-in cookie, and the pages. |
| `Entities.kt` | The stored data (`Poll`, `PollOption`, `Vote`), who is allowed to do what with it, and the `Voter` that those rules are about. |
| `PollService.kt` | Creating and editing polls, and voting. It holds the only copy of the voting rules. |
| `Shell.kt` | The page chrome around every page, and the not-found page. |
| `CreatePage.kt`, `PollPage.kt`, `AdminPage.kt` | The three pages. |
| `PollForm.kt` | The question-and-options form, which the create page and the admin page share. |
| `ui/` | The small UI components the pages are built from, such as buttons, cards, and inputs, and the toasts. |

### Who can do what

Pollster has no accounts, so every rule is about what a browser holds:

- **Seeing a poll** needs only its link. Knowing the slug, such as `acorn-ballet-irony`, is what the
  voting link is for.
- **Voting** needs a name. Picking a name on a poll signs the browser in under that name on that poll,
  and a cookie remembers it. Votes are matched by name, case-insensitively, so anyone who picks
  "Alice" can change Alice's votes. A browser can vote only under the name it picked.
- **Editing a poll** needs its admin token, which is the last part of the admin link. The admin page
  acts with the token from its URL, and the database rules check every write against it.
- **Changing a poll's link** needs the admin token too, and is possible only until someone first
  opens the voting page. After that, people may already have the link, so the rules refuse the
  change, whichever page asks. Anything that loads the voting page counts as opening it, including a
  chat app that fetches the link to show a preview, so change the link before sharing it.

These rules live on the entities, in `Entities.kt`, and `jetlin-db` enforces them on every read and
write. A page can't skip them by mistake.

## Change the styling

The pages use Tailwind classes, written as string literals in the Kotlin source. The Tailwind CLI
scans the source for them and compiles `src/main/css/app.css` into
`src/main/resources/pollster/app.css`. The compiled file is committed, so running the app doesn't
need node. After adding or changing a class, rebuild it:

```bash
npm install
npm run build
```

Write each class name in full. Tailwind finds only the class names it sees in the source, so a class
assembled at runtime, such as `"bg-" + color`, gets no CSS.

## Change the database

The entities in `Entities.kt` declare the tables. `db/schema.json` is the recorded copy of that
schema, and `db/migrations/` holds the SQL that gets a database from one version to the next. After
changing an entity, run the following command, then review and commit the migration it writes:

```bash
./gradlew dbDiff --name=what_changed
```

`./gradlew build` fails if the entities and `db/schema.json` disagree, so a forgotten migration
shows up before deployment.

The app applies pending migrations itself when it starts, before it opens the database, so a
deployment that changes the schema needs no extra step. It records them in the same table as
`./gradlew dbMigrate`, which you can still use on the database in `data/` without starting the app.
The Docker image includes the migrations, and finds them through the `POLLSTER_MIGRATIONS` variable.

## Tests

`src/test` drives the pages headlessly with `jetlin-testing`, with no browser or server involved. The
tests describe what a user does and sees. Some of them check things that a browser test can't: that
a vote re-sends only the option cards and the summary, and that a write that the rules refuse leaves
nothing behind.
