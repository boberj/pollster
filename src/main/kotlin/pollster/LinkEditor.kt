package pollster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Div
import jetlin.html.Form
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import pollster.ui.ButtonSize
import pollster.ui.ButtonVariant
import pollster.ui.FieldError
import pollster.ui.UiButton
import pollster.ui.UiInput

/**
 * Lets the poll's admin replace its generated link, such as `/poll/acorn-ballet-irony`, with one
 * they choose, until someone opens the poll.
 *
 * It reads [Poll.visited] live, so the moment anyone opens the voting page, the option disappears
 * from every page that shows it.
 *
 * It needs a [Voter] that holds the poll's admin token, because renaming is an edit.
 *
 * @param onRenamed called with the new slug after a rename, so the page can update its links.
 */
@Composable
context(admin: Voter)
fun LinkEditor(polls: PollService, poll: Poll, onRenamed: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    if (poll.visited) return

    Div({ classes("space-y-2 text-left"); testTag("link-editor") }) {
        if (!editing) {
            P({ classes("text-sm text-muted-foreground") }) {
                Text("Want a nicer link? You can change it until someone opens the poll. ")
                jetlin.html.Button({
                    type("button")
                    testTag("change-link")
                    classes("font-medium text-primary underline-offset-4 hover:underline")
                    onClick {
                        draft = poll.slug
                        problem = null
                        editing = true
                    }
                }) { Text("Change link") }
            }
            return@Div
        }

        Form({
            classes("space-y-2")
            testTag("link-form")
            onSubmit { values ->
                draft = values["slug"] ?: draft
                when (val result = polls.rename(poll, draft)) {
                    is PollService.Renamed.Done -> {
                        editing = false
                        onRenamed(result.slug)
                    }
                    is PollService.Renamed.Refused -> problem = result.message
                }
            }
        }) {
            Div({ classes("flex items-center gap-2") }) {
                Span({ classes("shrink-0 text-sm text-muted-foreground") }) { Text("/poll/") }
                UiInput {
                    name("slug")
                    testTag("slug")
                    attr("aria-label", "Poll link")
                    value(draft)
                    onInput(150) { draft = it }
                }
            }
            val preview = normalizeSlug(draft)
            if (preview != draft.trim() && preview.isNotEmpty()) {
                P({ classes("text-xs text-muted-foreground"); testTag("slug-preview") }) {
                    Text("It will be saved as /poll/$preview")
                }
            }
            problem?.let { FieldError(it) { testTag("slug-error") } }
            Div({ classes("flex gap-2") }) {
                jetlin.html.Button({
                    type("submit")
                    testTag("save-link")
                    classes(pollster.ui.buttonClasses(ButtonVariant.Default, ButtonSize.Small))
                }) { Text("Save link") }
                UiButton(ButtonVariant.Ghost, ButtonSize.Small, attrs = {
                    testTag("cancel-link")
                    onClick { editing = false }
                }) { Text("Cancel") }
            }
        }
    }
}
