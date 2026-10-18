package dev.tntcoating.gui;

import dev.tntcoating.modules.ChatTools;
import java.util.List;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;

public final class ChatToolsScreen extends WindowScreen {
    private final String player;
    private final List<ChatTools.Option> options;
    private final Consumer<ChatTools.Option> action;

    public ChatToolsScreen(GuiTheme theme, String player, List<ChatTools.Option> options,
                           Consumer<ChatTools.Option> action) {
        super(theme, "Chat Tools: " + player);
        this.player = player;
        this.options = options;
        this.action = action;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Choose a command for " + player + ". Detected commands are marked with *."));
        var table = add(theme.table()).expandX().widget();
        for (ChatTools.Option option : options) {
            var button = table.add(theme.button(option.label() + (option.detected() ? " *" : ""))).expandX().widget();
            button.tooltip = option.command();
            button.action = () -> action.accept(option);
            table.row();
        }
    }
}
