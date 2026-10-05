package pollster.ui

import androidx.compose.runtime.Composable
import jetlin.html.A
import jetlin.html.AttrsScope
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H2
import jetlin.html.Input
import jetlin.html.Label
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Path
import jetlin.html.Span
import jetlin.html.Svg
import jetlin.html.Text

/*
 * Pollster's UI components: buttons, cards, inputs, and the other small pieces the pages are built
 * from, styled with Tailwind classes.
 *
 * Every class name is a whole string literal, never assembled from parts: Tailwind finds class names
 * by scanning this source for literal strings, so "bg-" + tone would produce no CSS at all.
 */

/** Joins class lists, skipping empty ones. */
fun cn(vararg classes: String?): String = classes.filterNot { it.isNullOrBlank() }.joinToString(" ")

/** A [Button]'s look. */
enum class ButtonVariant { Default, Outline, Secondary, Ghost }

/** A [Button]'s size. */
enum class ButtonSize { Default, Small, Large, Icon }

private const val BUTTON_BASE =
    "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium " +
        "transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring " +
        "focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0"

/** The classes for a button with [variant] and [size]. Links styled as buttons use them too. */
fun buttonClasses(variant: ButtonVariant = ButtonVariant.Default, size: ButtonSize = ButtonSize.Default): String {
    val look = when (variant) {
        ButtonVariant.Default -> "bg-primary text-primary-foreground hover:bg-primary/90"
        ButtonVariant.Outline -> "border border-input bg-background hover:bg-accent hover:text-accent-foreground"
        ButtonVariant.Secondary -> "bg-secondary text-secondary-foreground hover:bg-secondary/80"
        ButtonVariant.Ghost -> "hover:bg-accent hover:text-accent-foreground"
    }
    val dimensions = when (size) {
        ButtonSize.Default -> "h-10 px-4 py-2"
        ButtonSize.Small -> "h-9 rounded-md px-3"
        ButtonSize.Large -> "h-11 rounded-md px-8"
        ButtonSize.Icon -> "h-10 w-10"
    }
    return cn(BUTTON_BASE, look, dimensions)
}

/**
 * A button.
 *
 * @param extraClasses classes added after the variant's own, such as `w-full`.
 * @param attrs any other attributes and handlers, such as `onClick`.
 */
@Composable
fun UiButton(
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    extraClasses: String? = null,
    attrs: AttrsScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Button({
        type("button")
        classes(cn(buttonClasses(variant, size), extraClasses))
        attrs()
    }) { content() }
}

/**
 * A link that looks like a button.
 *
 * By default it navigates without a page load, like any Jetlin [Link].
 *
 * @param newTab opens the link in a new tab instead. It's then a plain `<a target="_blank">`, which
 *   the browser handles alone, so this page and its session stay as they are.
 */
@Composable
fun ButtonLink(
    href: String,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    extraClasses: String? = null,
    newTab: Boolean = false,
    attrs: AttrsScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val classes = cn(buttonClasses(variant, size), extraClasses)
    if (newTab) {
        A({
            href(href)
            attr("target", "_blank")
            // The new page gets no handle on this one through window.opener.
            attr("rel", "noopener")
            classes(classes)
            attrs()
        }) { content() }
    } else {
        Link(href, { classes(classes); attrs() }) { content() }
    }
}

/** A card: the white, rounded panel that every page is built from. */
@Composable
fun Card(extraClasses: String? = null, attrs: AttrsScope.() -> Unit = {}, content: @Composable () -> Unit) {
    Div({
        classes(cn("rounded-lg border bg-card text-card-foreground shadow-sm", extraClasses))
        attrs()
    }) { content() }
}

@Composable
fun CardHeader(extraClasses: String? = null, content: @Composable () -> Unit) {
    Div({ classes(cn("flex flex-col space-y-1.5 p-6", extraClasses)) }) { content() }
}

@Composable
fun CardTitle(extraClasses: String? = null, attrs: AttrsScope.() -> Unit = {}, content: @Composable () -> Unit) {
    H2({
        classes(cn("text-2xl font-semibold leading-none tracking-tight", extraClasses))
        attrs()
    }) { content() }
}

@Composable
fun CardDescription(attrs: AttrsScope.() -> Unit = {}, content: @Composable () -> Unit) {
    P({
        classes("text-sm text-muted-foreground")
        attrs()
    }) { content() }
}

@Composable
fun CardContent(extraClasses: String? = null, content: @Composable () -> Unit) {
    Div({ classes(cn("p-6 pt-0", extraClasses)) }) { content() }
}

private const val INPUT_CLASSES =
    "flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-base " +
        "placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring " +
        "focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 md:text-sm"

/** A text input. Bind it to state in [attrs], for example with `bind(field)`. */
@Composable
fun UiInput(extraClasses: String? = null, attrs: AttrsScope.() -> Unit) {
    Input({
        type("text")
        classes(cn(INPUT_CLASSES, extraClasses))
        attrs()
    })
}

/** A form label. */
@Composable
fun UiLabel(forId: String? = null, extraClasses: String? = null, content: @Composable () -> Unit) {
    Label({
        classes(cn("text-sm font-medium leading-none", extraClasses))
        if (forId != null) attr("for", forId)
    }) { content() }
}

/** A validation message under a field. */
@Composable
fun FieldError(message: String, attrs: AttrsScope.() -> Unit = {}) {
    P({
        classes("text-sm text-destructive")
        attrs()
    }) { Text(message) }
}

/** A horizontal bar that's [percent] full. */
@Composable
fun Progress(percent: Int, extraClasses: String? = null) {
    Div({
        classes(cn("relative h-2 w-full overflow-hidden rounded-full bg-secondary", extraClasses))
        attr("role", "progressbar")
        attr("aria-valuemin", "0")
        attr("aria-valuemax", "100")
        attr("aria-valuenow", percent.toString())
    }) {
        Div({
            classes("h-full bg-primary transition-all")
            style("width: $percent%")
        })
    }
}

/**
 * A small circle with a voter's initial. Hovering shows the whole name.
 *
 * The name is in a `title` attribute, which the browser shows as a tooltip without any client-side
 * code.
 */
@Composable
fun VoterAvatar(name: String) {
    Span({
        classes("relative flex h-6 w-6 shrink-0 overflow-hidden rounded-full border-2 border-background shadow-sm")
        attr("title", name)
    }) {
        Span({
            classes("flex h-full w-full items-center justify-center rounded-full bg-secondary text-secondary-foreground font-bold text-xs")
        }) { Text(name.trim().take(1).uppercase()) }
    }
}

/**
 * One radio button with its label.
 *
 * Native radio buttons, so the browser handles keyboard navigation between them. Checking one reports
 * to the server, which stores the choice and re-renders all of them.
 */
@Composable
fun RadioItem(id: String, group: String, checked: Boolean, label: String, onSelect: () -> Unit) {
    Div({ classes("flex items-center space-x-2") }) {
        Input({
            type("radio")
            id(id)
            testTag(id)
            name(group)
            classes("h-4 w-4 cursor-pointer accent-primary")
            checked(checked)
            onChecked { if (it) onSelect() }
        })
        UiLabel(forId = id, extraClasses = "font-normal cursor-pointer") { Text(label) }
    }
}

/**
 * The icons Pollster uses. The paths are from the Lucide icon set, which draws every icon with
 * 2-pixel strokes in a 24×24 box.
 */
enum class Icon(val paths: List<String>) {
    Vote(listOf("m9 12 2 2 4-4", "M5 7c0-1.1.9-2 2-2h10a2 2 0 0 1 2 2v12H5V7Z", "M22 19H2")),
    Frown(listOf(CIRCLE, "M16 16s-1.5-2-4-2-4 2-4 2", "M9 9h.01", "M15 9h.01")),
    ExternalLink(listOf("M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")),
    PlusCircle(listOf(CIRCLE, "M8 12h8", "M12 8v8")),
    Trash(
        listOf(
            "M3 6h18",
            "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
            "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
            "M10 11v6",
            "M14 11v6",
        ),
    ),
    Copy(listOf("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")),
    Check(listOf("M20 6 9 17l-5-5")),
    Refresh(
        listOf(
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        ),
    ),
    UserSwitch(listOf("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8z", "M22 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75")),
}

/** A full circle of radius 10, centered in Lucide's 24×24 box, as a path. */
private const val CIRCLE = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0"

/**
 * Draws [icon] as an inline SVG.
 *
 * @param extraClasses sizing and color, such as `h-4 w-4 text-primary`.
 */
@Composable
fun UiIcon(icon: Icon, extraClasses: String? = "h-4 w-4") {
    Svg({
        attr("viewBox", "0 0 24 24")
        attr("fill", "none")
        attr("stroke", "currentColor")
        attr("stroke-width", "2")
        attr("stroke-linecap", "round")
        attr("stroke-linejoin", "round")
        attr("aria-hidden", "true")
        classes(extraClasses)
    }) {
        for (d in icon.paths) Path({ attr("d", d) })
    }
}
