package polycube.polycoin.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycoin.PolyCoin;

public abstract class PolyCoinCommand {
    private final String name;
    private final String description;
    private final String usage;
    private final PermissionLevel permissionLevel;
    private final boolean hasAlias;

    public PolyCoinCommand(String name, String description, String usage, PermissionLevel permissionLevel) {
        this.name = name;
        this.description = description;
        this.usage = usage;
        this.permissionLevel = permissionLevel;
        this.hasAlias = false;
    }

    public PolyCoinCommand(String name, String description, String usage, PermissionLevel permissionLevel, boolean hasAlias) {
        this.name = name;
        this.description = description;
        this.usage = usage;
        this.permissionLevel = permissionLevel;
        this.hasAlias = hasAlias;
    }

    protected String getName() {
        return name;
    }

    protected String getDescription() {
        return description + (permissionLevel.id() == 0 ? "." : " (" + permissionLevel.getSerializedName() + " only).");
    }

    protected String getUsage() {
        return "/" + PolyCoin.MOD_ID + " " + name + (usage.isBlank() ? "" : " " + usage) + (hasAlias ? " (alias: /" + name + ")" : "");
    }

    protected String getFullDescription() {
        return "\n" + getUsage() + "\n    - " + getDescription();
    }

    protected PermissionLevel getPermissionLevel() {
        return this.permissionLevel;
    }

    protected boolean hasAlias() {
        return this.hasAlias;
    }

    public LiteralArgumentBuilder<CommandSourceStack> getCommand() {
        return Commands.literal(name)
                .requires(source -> hasPermission(source, permissionLevel))
                .executes(e -> execute(e.getSource()))
                .then(Commands.literal("help").executes(e -> {
                    e.getSource().sendSuccess(() -> Component.literal(getFullDescription()), false);
                    return 1;
                }));
    }

    protected boolean hasPermission(CommandSourceStack source, PermissionLevel permissionLevel) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(permissionLevel));
    }

    protected int execute(CommandSourceStack source) {
        source.sendFailure(Component.literal("Incomplete command! Usage : " + getUsage()));
        return 0;
    }
}
