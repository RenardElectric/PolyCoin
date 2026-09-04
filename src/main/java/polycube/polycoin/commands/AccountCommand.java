package polycube.polycoin.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;

public final class AccountCommand extends PolyCoinCommand {
    private static final String ID_ARGUMENT = "account";
    private final @Nullable String ownerArgument;

    public AccountCommand() {
        this(null);
    }

    AccountCommand(@Nullable String ownerArgument) {
        super(
                "account",
                "Lists, creates, deletes, inspects, and modifies your accounts",
                "list [currencyId] | info <id> | transfer <from> <to> <amount> | create <id> <name> <icon> <currencyId> | delete <id> [confirm] | modify <id> <name|icon|currencyId> [value]",
                PermissionLevel.ALL
        );
        this.ownerArgument = ownerArgument;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return addSubcommands(super.getCommand(name, buildContext), buildContext);
    }

    <T extends ArgumentBuilder<CommandSourceStack, T>> T addSubcommands(T command, CommandBuildContext buildContext) {
        return command
                .then(Commands.literal("list")
                        .executes(context -> listAccounts(context, null))
                        .then(Commands.argument("currencyId", StringArgumentType.word())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> listAccounts(
                                        context,
                                        StringArgumentType.getString(context, "currencyId")
                                ))
                        )
                )
                .then(Commands.literal("info").then(
                        Commands.argument(ID_ARGUMENT, StringArgumentType.word())
                                .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, ownerArgument))
                                .executes(context -> showAccountInfo(
                                        context,
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                ))
                .then(transferCommand())
                .then(createCommand(buildContext))
                .then(deleteCommand())
                .then(modifyCommand(buildContext));
    }

    private int listAccounts(CommandContext<CommandSourceStack> context, @Nullable String rawCurrencyId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context, ownerArgument);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency filter = null;
        if (rawCurrencyId != null) {
            filter = findCurrency(source, data, rawCurrencyId);
            if (filter == null) return 0;
        }

        var accountIds = filter == null ? data.getAccountIds(owner) : data.getAccountIds(owner, filter);
        var message = Component.literal(ownerArgument == null ? "Your accounts" : "Accounts for " + owner.name());
        if (filter != null) message.append(" for " + filter.id().getPath());
        message.append(" (" + accountIds.size() + "):");

        if (accountIds.isEmpty()) {
            message.append("\nNo accounts found.");
        } else {
            for (String accountId : accountIds) {
                PolyCoinEconomyAccount account = data.getAccount(owner, accountId);
                if (account == null) continue;
                PolyCoinEconomyCurrency currency = account.currency();
                message.append("\n- " + accountId + " - ").append(account.name())
                        .append(": ").append(currency.formatValueComponent(account.balance(), true))
                        .append(" [" + currency.id().getPath() + "]");
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private int showAccountInfo(CommandContext<CommandSourceStack> context, String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);

        PolyCoinEconomyAccount account = selection.account();
        PolyCoinEconomyCurrency currency = account.currency();
        String defaultAccountId = selection.data().defaultAccount(selection.owner(), currency);
        var message = Component.literal("Account: " + account.id())
                .append("\nName: ").append(account.name())
                .append("\nOwner: ").append(Component.literal(selection.owner().name()))
                .append(" (" + account.owner() + ")")
                .append("\nIcon: " + BuiltInRegistries.ITEM.getKey(account.iconItem()))
                .append("\nCurrency: " + currency.id() + " - ").append(currency.name())
                .append("\nBalance: ").append(currency.formatValueComponent(account.balance(), true))
                .append("\nMain account: " + (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(account.id()) ? "yes" : "no"))
                .append("\nDefault for this currency: " + (account.id().getPath().equals(defaultAccountId) ? "yes" : "no"));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private ArgumentBuilder<CommandSourceStack, ?> transferCommand() {
        return Commands.literal("transfer").then(
                Commands.argument("from", StringArgumentType.string())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, ownerArgument))
                        .then(Commands.argument("to", StringArgumentType.string())
                                .suggests((context, builder) -> AccountArgument.suggestTransferTargets(context, builder, ownerArgument, "from"))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(this::transfer)
                                )
                        )
        );
    }

    private int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), false);
        AccountSelection selection = findAccount(context, StringArgumentType.getString(context, "from"));
        PolyCoinEconomyAccount target = AccountArgument.getAccount(
                selection.data(), selection.owner(), StringArgumentType.getString(context, "to")
        );
        var result = selection.data().transfer(selection.account(), target, amount);
        if (!result.successful()) {
            context.getSource().sendFailure(result.message());
            return 0;
        }
        var message = Component.literal("Transferred ")
                .append(selection.account().currency().formatValueComponent(amount, true))
                .append(" from ").append(selection.account().name())
                .append(" to ").append(target.name())
                .append(" for " + selection.owner().name() + ".");
        context.getSource().sendSuccess(() -> message, ownerArgument != null);
        return 1;
    }

    private ArgumentBuilder<CommandSourceStack, ?> createCommand(CommandBuildContext buildContext) {
        return Commands.literal("create").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word()).then(
                        Commands.argument("name", StringArgumentType.string()).then(
                                Commands.argument("icon", ItemArgument.item(buildContext)).then(
                                        Commands.argument("currencyId", StringArgumentType.word())
                                                .suggests(CurrencyArgument::suggestCurrencies)
                                                .executes(context -> createAccount(
                                                        context,
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

    private ArgumentBuilder<CommandSourceStack, ?> deleteCommand() {
        return Commands.literal("delete").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, ownerArgument))
                        .executes(context -> requestDeletion(
                                context,
                                StringArgumentType.getString(context, ID_ARGUMENT)
                        ))
                        .then(Commands.literal("confirm").executes(context -> deleteAccount(
                                context,
                                StringArgumentType.getString(context, ID_ARGUMENT)
                        )))
        );
    }

    private ArgumentBuilder<CommandSourceStack, ?> modifyCommand(CommandBuildContext buildContext) {
        return Commands.literal("modify").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, ownerArgument))
                        .then(Commands.literal("name")
                                .executes(context -> queryName(
                                        context,
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(context -> setName(
                                                context,
                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                StringArgumentType.getString(context, "value")
                                        )))
                        )
                        .then(Commands.literal("icon")
                                .executes(context -> queryIcon(
                                        context,
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                                .then(Commands.argument("value", ItemArgument.item(buildContext))
                                        .executes(context -> setIcon(
                                                context,
                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                ItemArgument.getItem(context, "value").item().value()
                                        )))
                        )
                        .then(Commands.literal("currencyId")
                                .executes(context -> queryCurrency(
                                        context,
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests(CurrencyArgument::suggestCurrencies)
                                        .executes(context -> setCurrency(
                                                context,
                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                StringArgumentType.getString(context, "value")
                                        )))
                        )
        );
    }

    private int createAccount(
            CommandContext<CommandSourceStack> context,
            String rawId,
            String name,
            Item icon,
            String rawCurrencyId
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context, ownerArgument);

        Identifier id = AccountArgument.parseId(rawId);
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

        PolyCoinEconomyAccount account = data.createAccount(owner, id, name, icon, currency);
        if (account == null) {
            source.sendFailure(Component.literal("Account already exists: " + id.getPath()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Created account " + id.getPath() + " (" + name + ") using currency "
                        + currency.id().getPath() + " for " + owner.name() + " with a zero balance."),
                ownerArgument != null
        );
        return 1;
    }

    private int requestDeletion(CommandContext<CommandSourceStack> context, String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context, ownerArgument);

        Identifier id = AccountArgument.parseId(rawId);
        if (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(id)) {
            source.sendFailure(Component.literal("The main account cannot be deleted."));
            return 0;
        }

        PolyCoinEconomyAccount account = AccountArgument.getAccount(
                PolyCoin.INSTANCE.getData(source.getServer()), owner, id.getPath()
        );
        source.sendFailure(Component.literal(
                "This will permanently delete account " + id.getPath() + " with balance "
                        + account.currency().formatValue(account.balance(), true) + " "
                        + account.currency().displayName() + " owned by " + owner.name()
                        + ". Run " + deletionCommand(owner, id) + " to continue."
        ));
        return 0;
    }

    private int deleteAccount(CommandContext<CommandSourceStack> context, String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context, ownerArgument);

        Identifier id = AccountArgument.parseId(rawId);
        if (PolyCoinEconomyData.MAIN_ACCOUNT_ID.equals(id)) {
            source.sendFailure(Component.literal("The main account cannot be deleted."));
            return 0;
        }

        PolyCoinEconomyAccount deleted = PolyCoin.INSTANCE.getData(source.getServer())
                .deleteAccount(owner, id);
        if (deleted == null) {
            source.sendFailure(Component.literal("Unknown account: " + rawId));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Deleted account " + id.getPath() + " for " + owner.name() + "."), ownerArgument != null);
        return 1;
    }

    private int queryName(CommandContext<CommandSourceStack> context, String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        source.sendSuccess(
                () -> Component.literal(selection.account().id().getPath() + " name: "
                        + selection.account().displayName()),
                false
        );
        return 1;
    }

    private int setName(CommandContext<CommandSourceStack> context, String rawId, String value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (value.isBlank()) {
            source.sendFailure(Component.literal("Account name cannot be blank."));
            return 0;
        }

        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        selection.data().updateAccount(
                selection.owner(), account.id(), value, account.iconItem(), account.currency()
        );
        source.sendSuccess(
                () -> Component.literal("Set " + account.id().getPath() + " name to " + value + " for " + selection.owner().name() + "."),
                ownerArgument != null
        );
        return 1;
    }

    private int queryIcon(CommandContext<CommandSourceStack> context, String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        Identifier iconId = BuiltInRegistries.ITEM.getKey(selection.account().iconItem());
        source.sendSuccess(
                () -> Component.literal(selection.account().id().getPath() + " icon: " + iconId),
                false
        );
        return 1;
    }

    private int setIcon(CommandContext<CommandSourceStack> context, String rawId, Item value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        selection.data().updateAccount(
                selection.owner(), account.id(), account.displayName(), value, account.currency()
        );
        Identifier iconId = BuiltInRegistries.ITEM.getKey(value);
        source.sendSuccess(
                () -> Component.literal("Set " + account.id().getPath() + " icon to " + iconId + " for " + selection.owner().name() + "."),
                ownerArgument != null
        );
        return 1;
    }

    private int queryCurrency(CommandContext<CommandSourceStack> context, String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        source.sendSuccess(
                () -> Component.literal(selection.account().id().getPath() + " currencyId: "
                        + selection.account().currencyId().getPath()),
                false
        );
        return 1;
    }

    private int setCurrency(CommandContext<CommandSourceStack> context, String rawId, String rawCurrencyId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
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
                selection.owner(), account.id(), account.displayName(), account.iconItem(), currency
        );
        source.sendSuccess(
                () -> Component.literal("Set " + account.id().getPath() + " currencyId to "
                        + currency.id().getPath() + " for " + selection.owner().name() + "."),
                ownerArgument != null
        );
        return 1;
    }

    private AccountSelection findAccount(
            CommandContext<CommandSourceStack> context, String rawId
    ) throws CommandSyntaxException {
        GameProfile owner = AccountArgument.getOwner(context, ownerArgument);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        return new AccountSelection(owner, data, AccountArgument.getAccount(data, owner, rawId));
    }

    private String deletionCommand(GameProfile owner, Identifier id) {
        String prefix = ownerArgument == null
                ? "/" + PolyCoin.MOD_ID + " account"
                : "/" + PolyCoin.MOD_ID + " admin account " + owner.name();
        return prefix + " delete " + id.getPath() + " confirm";
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

    private record AccountSelection(
            GameProfile owner,
            PolyCoinEconomyData data,
            PolyCoinEconomyAccount account
    ) {}
}
