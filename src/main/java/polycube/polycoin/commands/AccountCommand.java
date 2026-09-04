package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;

public final class AccountCommand extends PolyCoinCommand {
    private static final String ID_ARGUMENT = "account";

    public AccountCommand() {
        super(
                "account",
                "Creates, deletes, inspects, and modifies your accounts",
                "create <id> <name> <icon> <currencyId> | delete <id> [confirm] | modify <id> <name|icon|currencyId> [value]",
                PermissionLevel.ALL
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return super.getCommand(name, buildContext)
                .then(createCommand(buildContext))
                .then(deleteCommand())
                .then(modifyCommand(buildContext));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createCommand(CommandBuildContext buildContext) {
        return Commands.literal("create").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word()).then(
                        Commands.argument("name", StringArgumentType.string()).then(
                                Commands.argument("icon", ItemArgument.item(buildContext)).then(
                                        Commands.argument("currencyId", StringArgumentType.word())
                                                .suggests(CurrencyArgument::suggestCurrencies)
                                                .executes(context -> createAccount(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, ID_ARGUMENT),
                                                        StringArgumentType.getString(context, "name"),
                                                        ItemArgument.getItem(context, "icon").item().value(),
                                                        StringArgumentType.getString(context, "currencyId")
                                                ))
                                )
                        )
                )
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> deleteCommand() {
        return Commands.literal("delete").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word())
                        .suggests(AccountArgument::suggestAccounts)
                        .executes(context -> requestDeletion(
                                context.getSource(),
                                StringArgumentType.getString(context, ID_ARGUMENT)
                        ))
                        .then(Commands.literal("confirm").executes(context -> deleteAccount(
                                context.getSource(),
                                StringArgumentType.getString(context, ID_ARGUMENT)
                        )))
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> modifyCommand(CommandBuildContext buildContext) {
        return Commands.literal("modify").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word())
                        .suggests(AccountArgument::suggestAccounts)
                        .then(Commands.literal("name")
                                .executes(context -> queryName(
                                        context.getSource(),
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(context -> setName(
                                                context.getSource(),
                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                StringArgumentType.getString(context, "value")
                                        )))
                        )
                        .then(Commands.literal("icon")
                                .executes(context -> queryIcon(
                                        context.getSource(),
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                                .then(Commands.argument("value", ItemArgument.item(buildContext))
                                        .executes(context -> setIcon(
                                                context.getSource(),
                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                ItemArgument.getItem(context, "value").item().value()
                                        )))
                        )
                        .then(Commands.literal("currencyId")
                                .executes(context -> queryCurrency(
                                        context.getSource(),
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests(CurrencyArgument::suggestCurrencies)
                                        .executes(context -> setCurrency(
                                                context.getSource(),
                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                StringArgumentType.getString(context, "value")
                                        )))
                        )
        );
    }

    private static int createAccount(
            CommandSourceStack source,
            String rawId,
            String name,
            Item icon,
            String rawCurrencyId
    ) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        Identifier id = parseAccountId(source, rawId);
        if (id == null) return 0;
        if (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(id)) {
            source.sendFailure(Component.literal("The main_account id is reserved."));
            return 0;
        }
        if (name.isBlank()) {
            source.sendFailure(Component.literal("Account name cannot be blank."));
            return 0;
        }

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = findCurrency(source, data, rawCurrencyId);
        if (currency == null) return 0;

        PolyCoinEconomyAccount account = data.createAccount(player.getGameProfile(), id, name, icon, currency);
        if (account == null) {
            source.sendFailure(Component.literal("Account already exists: " + id.getPath()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Created account " + id.getPath() + " (" + name + ") using currency "
                        + currency.id().getPath() + " with a zero balance."),
                false
        );
        return 1;
    }

    private static int requestDeletion(CommandSourceStack source, String rawId) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        Identifier id = parseAccountId(source, rawId);
        if (id == null) return 0;
        if (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(id)) {
            source.sendFailure(Component.literal("The main account cannot be deleted."));
            return 0;
        }

        PolyCoinEconomyAccount account = findAccount(source, player, id);
        if (account == null) return 0;
        source.sendFailure(Component.literal(
                "This will permanently delete account " + id.getPath() + " with balance "
                        + account.currency().formatValue(account.balance(), true) + " "
                        + account.currency().displayName() + ". Run /" + PolyCoin.MOD_ID
                        + " account delete " + id.getPath() + " confirm to continue."
        ));
        return 0;
    }

    private static int deleteAccount(CommandSourceStack source, String rawId) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        Identifier id = parseAccountId(source, rawId);
        if (id == null) return 0;
        if (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(id)) {
            source.sendFailure(Component.literal("The main account cannot be deleted."));
            return 0;
        }

        PolyCoinEconomyAccount deleted = PolyCoin.INSTANCE.getData(source.getServer())
                .deleteAccount(player.getGameProfile(), id);
        if (deleted == null) {
            source.sendFailure(Component.literal("Unknown account: " + rawId));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Deleted account " + id.getPath() + "."), false);
        return 1;
    }

    private static int queryName(CommandSourceStack source, String rawId) {
        AccountSelection selection = findAccount(source, rawId);
        if (selection == null) return 0;
        source.sendSuccess(
                () -> Component.literal(selection.account().id().getPath() + " name: "
                        + selection.account().displayName()),
                false
        );
        return 1;
    }

    private static int setName(CommandSourceStack source, String rawId, String value) {
        if (value.isBlank()) {
            source.sendFailure(Component.literal("Account name cannot be blank."));
            return 0;
        }

        AccountSelection selection = findAccount(source, rawId);
        if (selection == null) return 0;
        PolyCoinEconomyAccount account = selection.account();
        selection.data().updateAccount(
                selection.player().getGameProfile(), account.id(), value, account.iconItem(), account.currency()
        );
        source.sendSuccess(() -> Component.literal("Set " + account.id().getPath() + " name to " + value + "."), false);
        return 1;
    }

    private static int queryIcon(CommandSourceStack source, String rawId) {
        AccountSelection selection = findAccount(source, rawId);
        if (selection == null) return 0;
        Identifier iconId = BuiltInRegistries.ITEM.getKey(selection.account().iconItem());
        source.sendSuccess(
                () -> Component.literal(selection.account().id().getPath() + " icon: " + iconId),
                false
        );
        return 1;
    }

    private static int setIcon(CommandSourceStack source, String rawId, Item value) {
        AccountSelection selection = findAccount(source, rawId);
        if (selection == null) return 0;
        PolyCoinEconomyAccount account = selection.account();
        selection.data().updateAccount(
                selection.player().getGameProfile(), account.id(), account.displayName(), value, account.currency()
        );
        Identifier iconId = BuiltInRegistries.ITEM.getKey(value);
        source.sendSuccess(() -> Component.literal("Set " + account.id().getPath() + " icon to " + iconId + "."), false);
        return 1;
    }

    private static int queryCurrency(CommandSourceStack source, String rawId) {
        AccountSelection selection = findAccount(source, rawId);
        if (selection == null) return 0;
        source.sendSuccess(
                () -> Component.literal(selection.account().id().getPath() + " currencyId: "
                        + selection.account().currencyId().getPath()),
                false
        );
        return 1;
    }

    private static int setCurrency(CommandSourceStack source, String rawId, String rawCurrencyId) {
        AccountSelection selection = findAccount(source, rawId);
        if (selection == null) return 0;
        PolyCoinEconomyAccount account = selection.account();
        PolyCoinEconomyCurrency currency = findCurrency(source, selection.data(), rawCurrencyId);
        if (currency == null) return 0;

        if (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(account.id())
                && !PolyCoinEconomyData.MAIN_CURRENCY_ID.equals(currency.id())) {
            source.sendFailure(Component.literal("The main account currency cannot be changed."));
            return 0;
        }
        if (!account.currencyId().equals(currency.id()) && account.balance().signum() != 0) {
            source.sendFailure(Component.literal("An account balance must be zero before its currency can be changed."));
            return 0;
        }

        selection.data().updateAccount(
                selection.player().getGameProfile(), account.id(), account.displayName(), account.iconItem(), currency
        );
        source.sendSuccess(
                () -> Component.literal("Set " + account.id().getPath() + " currencyId to "
                        + currency.id().getPath() + "."),
                false
        );
        return 1;
    }

    private static @Nullable AccountSelection findAccount(CommandSourceStack source, String rawId) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return null;
        Identifier id = parseAccountId(source, rawId);
        if (id == null) return null;

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount account = findAccount(source, player, id);
        return account == null ? null : new AccountSelection(player, data, account);
    }

    private static @Nullable PolyCoinEconomyAccount findAccount(
            CommandSourceStack source,
            ServerPlayer player,
            Identifier id
    ) {
        PolyCoinEconomyAccount account = PolyCoin.INSTANCE.getData(source.getServer())
                .getAccount(player.getGameProfile(), id.getPath());
        if (account == null) source.sendFailure(Component.literal("Unknown account: " + id.getPath()));
        return account;
    }

    private static @Nullable PolyCoinEconomyCurrency findCurrency(
            CommandSourceStack source,
            PolyCoinEconomyData data,
            String rawId
    ) {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("Invalid currency id: " + rawId));
            return null;
        }

        PolyCoinEconomyCurrency currency = data.getCurrency(id);
        if (currency == null) source.sendFailure(Component.literal("Unknown currency: " + rawId));
        return currency;
    }

    private static @Nullable Identifier parseAccountId(CommandSourceStack source, String rawId) {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("Invalid account id. Use a PolyCoin id such as savings or polycoin:savings."));
        }
        return id;
    }

    private static @Nullable ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) source.sendFailure(Component.literal("This command can only be executed by a player."));
        return player;
    }

    private record AccountSelection(
            ServerPlayer player,
            PolyCoinEconomyData data,
            PolyCoinEconomyAccount account
    ) {}
}
