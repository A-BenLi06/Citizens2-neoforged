package net.citizensnpcs.api.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;

import net.minecraft.commands.CommandSourceStack;

/**
 * Splits a long list of lines into pages, with optional clickable previous/next links.
 * <p>
 * Ported unchanged apart from the recipient type ({@code CommandSender} → {@link CommandSourceStack}). The page-switcher
 * markup uses the {@code <click:run_command:…>} tags {@link TextParser} understands, so the links work the same.
 */
public class Paginator {
    private boolean console;
    private String header;
    private final List<String> lines = new ArrayList<>();
    private final Map<Integer, Component> componentLines = new HashMap<>();
    private IntFunction<String> pageCommand;
    private boolean pageSwitcher;

    public Paginator() {
    }

    public Paginator(Collection<String> lines) {
        this.lines.addAll(lines);
    }

    public Paginator(int initialLinesOfText) {
        for (int i = 0; i < initialLinesOfText; i++) {
            lines.add("");
        }
    }

    public void addLine(String line) {
        lines.add(line);
    }

    /** Native rows keep interactive command data separate from display markup. */
    public void addLine(Component line) {
        componentLines.put(lines.size(), line.copy());
        lines.add(line.getString());
    }

    public Paginator console(boolean console) {
        this.console = console;
        return this;
    }

    public Paginator enablePageSwitcher() {
        return enablePageSwitcher("page $page");
    }

    public Paginator enablePageSwitcher(String command) {
        return enablePageSwitcher(page -> command.replace("$page", Integer.toString(page)));
    }

    public Paginator enablePageSwitcher(IntFunction<String> command) {
        pageSwitcher = true;
        pageCommand = command;
        return this;
    }

    private int pageCount() {
        int linesPerPage = console ? CONSOLE_LINES_PER_PAGE : LINES_PER_PAGE;
        int pages = (int) Math.ceil((double) lines.size() / linesPerPage);
        return pages == 0 ? 1 : pages;
    }

    public String getPageText(int page) {
        int linesPerPage = console ? CONSOLE_LINES_PER_PAGE : LINES_PER_PAGE;
        int pages = pageCount();
        if (page <= 0 || page > pages)
            return null;

        int startIndex = linesPerPage * page - linesPerPage;
        int endIndex = Math.min(page * linesPerPage, lines.size());

        String pageDisplay = page + "/" + pages;
        if (pageSwitcher) {
            if (page > 1) {
                pageDisplay = "<click:run_command:" + TextParser.quoteTagArgument(pageCommand.apply(page - 1))
                        + "><hover:show_text:" + TextParser.quoteTagArgument(Messaging.tr("citizens.commands.page.previous"))
                        + "><</hover></click> <white>" + pageDisplay;
            }
            if (pages > 1 && page != pages) {
                pageDisplay += " <click:run_command:" + TextParser.quoteTagArgument(pageCommand.apply(page + 1))
                        + "><hover:show_text:" + TextParser.quoteTagArgument(Messaging.tr("citizens.commands.page.next"))
                        + ">></hover></click><white>";
            }
        }
        StringBuilder text = new StringBuilder(
                header == null ? "" : wrapHeader("[[" + header + " <white>" + pageDisplay));
        for (String line : lines.subList(startIndex, endIndex)) {
            text.append("\n").append(line);
        }
        return text.toString();
    }

    public boolean hasPage(int page) {
        return page > 0 && page <= pageCount();
    }

    public Paginator header(String header) {
        this.header = header;
        return this;
    }

    public boolean sendPage(CommandSourceStack sender, int page) {
        String text = getPageText(page);
        if (text == null)
            return false;
        if (!componentLines.isEmpty()) {
            var title = Component.empty();
            if (header != null) title.append(Messaging.minecraftComponentFromRawMessage(
                    wrapHeader("[[" + header + " <white>" + page + "/" + pageCount())));
            if (pageSwitcher) {
                if (page > 1) title.append(pageButton(page - 1, "<", "citizens.commands.page.previous"));
                if (page < pageCount()) title.append(pageButton(page + 1, ">", "citizens.commands.page.next"));
            }
            sender.sendSystemMessage(title);
            int count = console ? CONSOLE_LINES_PER_PAGE : LINES_PER_PAGE;
            int start = (page - 1) * count;
            for (int i = start; i < Math.min(start + count, lines.size()); i++) {
                Component line = componentLines.get(i);
                if (line == null) Messaging.send(sender, lines.get(i));
                else sender.sendSystemMessage(line.copy());
            }
            return true;
        }
        Messaging.send(sender, text);
        return true;
    }

    private Component pageButton(int page, String label, String hint) {
        return Component.literal(" " + label).withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, pageCommand.apply(page)))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Messaging.tr(hint)))));
    }

    public static String wrapHeader(Object string) {
        return "[[=====[ " + string.toString() + " [[]=====";
    }

    private static final int CONSOLE_LINES_PER_PAGE = 200;
    private static final int LINES_PER_PAGE = 9;
}
