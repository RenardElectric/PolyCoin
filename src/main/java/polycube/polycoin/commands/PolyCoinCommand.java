package polycube.polycoin.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycoin.PolyCoin;

import java.util.ArrayList;
import java.util.List;

public abstract class PolyCoinCommand {
    private final String name;
    private final String description;
    private final String usage;
    private final PermissionLevel permissionLevel;
    private final boolean hasQuickAlias;
    private final List<String> aliases;

    public PolyCoinCommand(String name, String description, String usage, PermissionLevel permissionLevel) {
        this(name, description, usage, permissionLevel, false);
    }

    public PolyCoinCommand(String name, String description, String usage, PermissionLevel permissionLevel, boolean hasQuickAlias) {
        this(name, description, usage, permissionLevel, hasQuickAlias, List.of());
    }

    public PolyCoinCommand(String name, String description, String usage, PermissionLevel permissionLevel, boolean hasQuickAlias, List<String> aliases) {
        this.name = name;
        this.description = description;
        this.usage = usage;
        this.permissionLevel = permissionLevel;
        this.hasQuickAlias = hasQuickAlias;
        this.aliases = aliases;
    }

    protected String getName() {
        return name;
    }

    protected String getDescription() {
        return description + (permissionLevel.id() == 0 ? "." : " (" + permissionLevel.getSerializedName() + " only).");
    }

    protected String getUsage() {
        return "/" + PolyCoin.MOD_ID + " " + name + (usage.isBlank() ? "" : " " + usage) + (hasQuickAlias ? " (/" + name + ")" : "") + (!aliases.isEmpty() ? " (aliases: " + String.join(", ", aliases) + ")" : "");
    }

    protected String getFullDescription() {
        return "\n" + getUsage() + "\n    - " + getDescription();
    }

    protected PermissionLevel getPermissionLevel() {
        return this.permissionLevel;
    }

    protected boolean hasQuickAlias() {
        return this.hasQuickAlias;
    }

    protected List<String> getAliases() {
        return this.aliases;
    }

    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return Commands.literal(name)
                    .requires(source -> hasPermission(source, permissionLevel))
                    .executes(e -> execute(e.getSource()))
                    .then(Commands.literal("help").executes(e -> {
                        e.getSource().sendSuccess(() -> Component.literal(getFullDescription()), false);
                        return 1;
                    }));

    }

    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return getCommand(name);
    }

    public List<LiteralArgumentBuilder<CommandSourceStack>> getCommands(CommandBuildContext buildContext) {
        var commands = new ArrayList<LiteralArgumentBuilder<CommandSourceStack>>();
        var aliases = new ArrayList<>(getAliases());
        aliases.add(name);
        for (String alias : aliases) {
            commands.add(getCommand(alias, buildContext));
        }
        return commands;
    }

    protected boolean hasPermission(CommandSourceStack source, PermissionLevel permissionLevel) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(permissionLevel));
    }

    protected int execute(CommandSourceStack source) {
        source.sendFailure(Component.literal("Incomplete command! Usage : " + getUsage()));
        return 0;
    }
}
