package pollster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jetlin.html.Div
import jetlin.html.Form
import jetlin.html.Text
import pollster.ui.ButtonSize
import pollster.ui.ButtonVariant
import pollster.ui.FieldError
import pollster.ui.Icon
import pollster.ui.RadioItem
import pollster.ui.UiButton
import pollster.ui.UiIcon
import pollster.ui.UiInput
import pollster.ui.UiLabel

/**
 * One option row in the form.
 *
 * @property key identifies the row for as long as the form is open, so removing a row removes only
 *   that row's markup instead of re-sending every row below it.
 * @property existing the stored option this row edits, or `null` for a new one.
 */
class OptionRow(val key: Int, val existing: PollOption?, text: String) {
    var text by mutableStateOf(text)
}

/**
 * What the poll form holds while it's open: the question, the type, and the option rows.
 *
 * It's plain server-side state. Validation reads it directly, so there's no form library and no
 * copy of the values in the browser.
 */
class PollFormState(question: String = "", multiple: Boolean = false, options: List<Pair<PollOption?, String>> = NEW_OPTIONS) {
    var question by mutableStateOf(question)
    var multiple by mutableStateOf(multiple)
    val rows = mutableStateListOf<OptionRow>()

    /**
     * Whether the user has tried to submit. Errors appear only after that, so the form doesn't open
     * covered in complaints, and then they follow the input live.
     */
    var submitted by mutableStateOf(false)

    private var nextKey = 0

    init {
        options.forEach { (existing, text) -> rows += OptionRow(nextKey++, existing, text) }
    }

    fun addRow() {
        rows += OptionRow(nextKey++, null, "")
    }

    fun removeRow(row: OptionRow) {
        if (rows.size > MIN_OPTIONS) rows.remove(row)
    }

    val questionError: String?
        get() = if (question.isBlank()) "Please enter a question for your poll." else null

    /**
     * The rows that will be saved: every row with some text.
     *
     * An empty row isn't an error. Pressing Enter in the last option adds one, so a form often ends
     * with a blank row, and saving simply leaves it out. On the admin page, clearing a stored option's
     * text removes that option, like its remove button does.
     */
    val filledRows: List<OptionRow>
        get() = rows.filter { it.text.isNotBlank() }

    val optionsError: String?
        get() = if (filledRows.size < MIN_OPTIONS) "Please provide at least two options." else null

    val isValid: Boolean
        get() = questionError == null && optionsError == null

    /**
     * Copies the values the browser submitted into the form.
     *
     * Typing is debounced, so a quick Enter can submit a value the server hasn't heard about yet. The
     * submit event carries every named field's current value, so the form uses those.
     */
    fun takeSubmitted(values: Map<String, String>) {
        values["question"]?.let { question = it }
        for (row in rows) values["option-${row.key}"]?.let { row.text = it }
    }

    companion object {
        const val MIN_OPTIONS = 2
        private val NEW_OPTIONS = listOf<Pair<PollOption?, String>>(null to "", null to "")
    }
}

/**
 * The poll form, used both to create a poll and to edit one.
 *
 * @param submitLabel the submit button's text.
 * @param onSubmit called with a valid form. It does the saving.
 */
@Composable
fun PollForm(state: PollFormState, submitLabel: String, onSubmit: () -> Unit) {
    Form({
        classes("space-y-8")
        testTag("poll-form")
        onSubmit { values ->
            state.takeSubmitted(values)
            state.submitted = true
            if (state.isValid) onSubmit()
        }
    }) {
        Div({ classes("space-y-2") }) {
            UiLabel(forId = "question", extraClasses = "text-lg") { Text("Poll Question") }
            UiInput {
                id("question")
                name("question")
                testTag("question")
                placeholder("e.g., Where should we go for dinner?")
                value(state.question)
                onInput(150) { state.question = it }
            }
            if (state.submitted) state.questionError?.let { FieldError(it) { testTag("question-error") } }
        }

        Div({ classes("space-y-3") }) {
            UiLabel(extraClasses = "text-lg") { Text("Poll Type") }
            Div({ classes("flex space-x-4 pt-1"); attr("role", "radiogroup") }) {
                RadioItem("single", "pollType", checked = !state.multiple, label = "Single Choice") { state.multiple = false }
                RadioItem("multiple", "pollType", checked = state.multiple, label = "Multiple Choice") { state.multiple = true }
            }
        }

        Div({ classes("space-y-4") }) {
            UiLabel(extraClasses = "text-lg") { Text("Options") }
            Div({ classes("space-y-2"); testTag("options") }) {
                state.rows.forEachIndexed { index, row ->
                    key(row.key) { OptionInput(state, row, index) }
                }
            }
            if (state.submitted) state.optionsError?.let { FieldError(it) }
            UiButton(ButtonVariant.Outline, extraClasses = "w-full", attrs = {
                testTag("add-option")
                attr("data-add-option", "")
                onClick { state.addRow() }
            }) {
                UiIcon(Icon.PlusCircle, "mr-2 h-4 w-4")
                Text("Add Option")
            }
        }

        jetlin.html.Button({
            type("submit")
            testTag("submit")
            classes("${pollster.ui.buttonClasses(ButtonVariant.Default, ButtonSize.Large)} w-full text-lg py-6")
        }) { Text(submitLabel) }
    }
}

/** One option's text field, with its remove button once there are more than two. */
@Composable
private fun OptionInput(state: PollFormState, row: OptionRow, index: Int) {
    Div({ testTag("option-row") }) {
        Div({ classes("flex items-center gap-2") }) {
            UiInput {
                name("option-${row.key}")
                testTag("option")
                // app.js moves to the next option on Enter, adding one after the last. Without it, Enter
                // would submit the form.
                attr("data-option", "")
                placeholder("Option ${index + 1}")
                attr("aria-label", "Option ${index + 1}")
                value(row.text)
                onInput(150) { row.text = it }
            }
            if (state.rows.size > PollFormState.MIN_OPTIONS) {
                UiButton(ButtonVariant.Ghost, ButtonSize.Icon, "text-muted-foreground hover:text-destructive", attrs = {
                    testTag("remove-option")
                    attr("aria-label", "Remove option")
                    onClick { state.removeRow(row) }
                }) { UiIcon(Icon.Trash) }
            }
        }
    }
}
