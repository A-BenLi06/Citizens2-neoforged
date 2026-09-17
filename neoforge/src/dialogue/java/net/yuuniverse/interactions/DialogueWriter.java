package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

/** A session-owned chat animation. Rendering never executes actions or advances the dialogue clock. */
final class DialogueWriter {
    private final List<Row> rows;
    private final WriteDialogueSettings settings;
    private int row, end, wait;

    DialogueWriter(List<Component> text, WriteDialogueSettings settings) {
        this.rows = text.stream().map(Row::new).toList();
        this.settings = settings;
    }

    /** Returns a changed frame, or null between animation steps and after completion. */
    List<Component> tick() {
        if (row == rows.size() || --wait > 0) return null;
        wait = settings.delay();
        Row current = rows.get(row);
        end = current.next(end, settings.mode());
        List<Component> frame = new ArrayList<>();
        for (int i = 0; i < row; i++) frame.add(rows.get(i).original);
        frame.add(current.prefix(end));
        if (end == current.text.length()) { row++; end = 0; }
        return frame;
    }

    private record Run(int start, int end, Component value, String literal) {}

    private static final class Row {
        final Component original;
        final List<Run> runs = new ArrayList<>();
        final StringBuilder text = new StringBuilder();

        Row(Component value) { original = value.copy(); append(original, Style.EMPTY); }

        private void append(Component value, Style inherited) {
            Style style = value.getStyle().applyTo(inherited);
            var click = style.getClickEvent();
            if (click != null && click.getValue().equals("/interactions skipdialogue")) {
                // A next control must appear whole, including its client-translatable label and hover text.
                atomic(value.copy().setStyle(style));
                return;
            }
            if (value.getContents() instanceof PlainTextContents literal) {
                String content = literal.text();
                int start = text.length();
                text.append(content);
                if (!content.isEmpty()) runs.add(new Run(start, text.length(), Component.literal(content).setStyle(style), content));
            } else {
                // Translation, keybind, score and NBT contents remain native client components, not server-language text.
                atomic(MutableComponent.create(value.getContents()).setStyle(style));
            }
            for (Component sibling : value.getSiblings()) append(sibling, style);
        }

        private void atomic(Component value) {
            int start = text.length();
            text.append('\uFFFC');
            runs.add(new Run(start, text.length(), value, null));
        }

        int next(int offset, WriteDialogueSettings.Mode mode) {
            if (offset == text.length()) return offset;
            if (mode == WriteDialogueSettings.Mode.CHARACTER)
                return offset + Character.charCount(Character.codePointAt(text, offset));
            // Retain leading/repeated/trailing whitespace exactly; formatting boundaries do not split words.
            while (offset < text.length() && space(Character.codePointAt(text, offset)))
                offset += Character.charCount(Character.codePointAt(text, offset));
            while (offset < text.length() && !space(Character.codePointAt(text, offset)))
                offset += Character.charCount(Character.codePointAt(text, offset));
            int trailing = offset;
            while (trailing < text.length() && space(Character.codePointAt(text, trailing)))
                trailing += Character.charCount(Character.codePointAt(text, trailing));
            return trailing == text.length() ? trailing : offset;
        }

        Component prefix(int limit) {
            if (limit == text.length()) return original;
            MutableComponent result = Component.empty();
            for (Run run : runs) {
                if (run.start >= limit) break;
                if (run.end <= limit) result.append(run.value);
                else result.append(Component.literal(run.literal.substring(0, limit - run.start)).setStyle(run.value.getStyle()));
            }
            return result;
        }

        private static boolean space(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint); }
    }
}
