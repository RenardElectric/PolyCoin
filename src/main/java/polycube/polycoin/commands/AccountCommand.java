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

    public AccountCommand() {
        super(
                "account",
                "Lists, creates, deletes, inspects, and modifies your accounts",
                "list [currencyId] | info [id] | default [id] | transfer <amount> [from <id>] [to <id>] | create <id> <name> <icon> [currencyId] | delete [id] [confirm] | modify [id] <name|icon|currencyId> [value]",
                PermissionLevel.ALL
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return super.getCommand(name, buildContext)
                .then(Commands.literal("list")
                        .executes(context -> listAccounts(context, null))
                        .then(Commands.argument("currencyId", StringArgumentType.string())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> listAccounts(
                                        context,
                                        StringArgumentType.getString(context, "currencyId")
                                ))
                        )
                )
                .then(Commands.literal("info").executes(context -> showAccountInfo(context, null)).then(
                        Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .suggests(AccountArgument::suggestAccounts)
                                .executes(context -> showAccountInfo(
                                        context,
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                ))
                .then(Commands.literal("default")
                        .executes(context -> defaultAccount(context, null))
                        .then(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .suggests(AccountArgument::suggestAccounts)
                                .executes(context -> defaultAccount(context, StringArgumentType.getString(context, ID_ARGUMENT)))
                        )
                )
                .then(transferCommand())
                .then(createCommand(buildContext))
                .then(deleteCommand())
                .then(modifyCommand(buildContext));
    }

    private int listAccounts(CommandContext<CommandSourceStack> context, @Nullable String rawCurrencyId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency filter = null;
        if (rawCurrencyId != null) {
            filter = CurrencyArgument.getCurrency(data, rawCurrencyId);
        }

        var accounts = filter == null ? data.getAccounts(owner.id()) : data.getAccounts(owner.id(), filter.getId());
        var message = CommandText.header(!AccountArgument.isActingAs(context) ? "Your accounts" : "Accounts for " + owner.name());
        if (filter != null) message.append(" • ").append(CommandText.currency(filter));
        message.append(CommandText.muted(" (" + accounts.size() + ")"));

        if (accounts.isEmpty()) {
            message.append("\nNo accounts found.");
        } else {
            for (PolyCoinEconomyAccount account : accounts.values()) {
                PolyCoinEconomyCurrency currency = CommandResult.require(account.getCurrency());
                message.append("\n  • ").append(CommandText.account(account));
                if (data.isDefaultAccount(owner.id(), account.getId())) message.append(CommandText.badge());
                message.append("\n    ").append(CommandText.amount(currency.formatValueComponent(account.balance(), true)));
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private int showAccountInfo(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);

        PolyCoinEconomyAccount account = selection.account();
        PolyCoinEconomyCurrency currency = CommandResult.require(account.getCurrency());
        var message = CommandText.header("Account details")
                .append(CommandText.field("Name", CommandText.value(account.name())))
                .append(CommandText.field("ID", CommandText.value(account.id())))
                .append(CommandText.field("Owner", CommandText.value(selection.owner().name())
                        .append(CommandText.muted(" (" + account.owner() + ")"))))
                .append(CommandText.field("Icon", CommandText.value(BuiltInRegistries.ITEM.getKey(account.iconItem()))))
                .append(CommandText.field("Currency", CommandText.currency(currency)))
                .append(CommandText.field("Balance", CommandText.amount(currency.formatValueComponent(account.balance(), true))))
                .append(CommandText.field("Default for this currency", CommandText.yesNo(selection.data().isDefaultAccount(account.owner(), account.getId()))));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private int defaultAccount(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        GameProfile owner = AccountArgument.getOwner(context);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        PolyCoinEconomyAccount account = AccountArgument.getAccount(data, owner.id(), rawId);
        if (rawId != null) CommandResult.require(data.setDefaultAccount(owner.id(), account.getId()));
        var message = (rawId == null ? CommandText.header("Default account") : CommandText.success("Default account updated"))
                .append(CommandText.field("Owner", CommandText.value(owner.name())))
                .append(CommandText.field("Account", CommandText.account(account)))
                .append(CommandText.field("Currency", CommandText.currency(CommandResult.require(account.getCurrency()))));
        context.getSource().sendSuccess(() -> message, rawId != null && AccountArgument.isActingAs(context));
        return 1;
    }

    private ArgumentBuilder<CommandSourceStack, ?> transferCommand() {
        return Commands.literal("transfer").then(AccountArgument.transferArguments(null, this::transfer));
    }

    private int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), false);
        var owner = AccountArgument.getOwner(context);
        var data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        var accounts = AccountArgument.getTransferAccounts(
                data, owner.id(), PolyCoinIdentifierArgument.getOptionalId(context, "from"),
                owner.id(), PolyCoinIdentifierArgument.getOptionalId(context, "to")
        );
        var currency = CommandResult.require(accounts.source().getCurrency());
        CommandResult.require(data.transfer(owner.id(), accounts.source().getId(), owner.id(), accounts.target().getId(), amount));
        var message = CommandText.success("Transfer complete")
                .append(CommandText.field("Amount", CommandText.amount(currency.formatValueComponent(amount, true))))
                .append(CommandText.field("From", CommandText.account(accounts.source())))
                .append(CommandText.field("To", CommandText.account(accounts.target())))
                .append(CommandText.field("Owner", CommandText.value(owner.name())));
        context.getSource().sendSuccess(() -> message, AccountArgument.isActingAs(context));
        return 1;
    }

    private ArgumentBuilder<CommandSourceStack, ?> createCommand(CommandBuildContext buildContext) {
        return Commands.literal("create").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.string()).then(
                        Commands.argument("name", StringArgumentType.string()).then(
                                Commands.argument("icon", ItemArgument.item(buildContext))
                                        .executes(context -> createAccount(
                                                context, StringArgumentType.getString(context, ID_ARGUMENT),
                                                StringArgumentType.getString(context, "name"),
                                                ItemArgument.getItem(context, "icon").item().value(), null
                                        )).then(
                                        Commands.argument("currencyId", StringArgumentType.string())
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
        return deletionArguments(Commands.literal("delete"))
                .then(deletionArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, "confirm"))));
    }

    private <T extends ArgumentBuilder<CommandSourceStack, T>> T deletionArguments(T command) {
        return command.executes(context -> requestDeletion(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                .then(Commands.literal("confirm").executes(context ->
                        deleteAccount(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> modifyCommand(CommandBuildContext buildContext) {
        return modificationArguments(Commands.literal("modify"), buildContext)
                .then(modificationArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, "name", "icon", "currencyId")), buildContext));
    }

    private <T extends ArgumentBuilder<CommandSourceStack, T>> T modificationArguments(T command, CommandBuildContext buildContext) {
        return command
                .then(Commands.literal("name")
                        .executes(context -> queryName(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(context -> setName(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("icon")
                        .executes(context -> queryIcon(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", ItemArgument.item(buildContext))
                                .executes(context -> setIcon(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        ItemArgument.getItem(context, "value").item().value()))))
                .then(Commands.literal("currencyId")
                        .executes(context -> queryCurrency(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> setCurrency(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))));
    }

    private int createAccount(
            CommandContext<CommandSourceStack> context,
            String rawId,
            String name,
            Item icon,
            @Nullable String rawCurrencyId
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        String id = AccountArgument.parseId(rawId);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawCurrencyId);
        PolyCoinEconomyAccount account = CommandResult.require(data.createAccount(owner.id(), id, name, icon, currency.getId()));

        source.sendSuccess(
                () -> CommandText.success("Account created")
                        .append(CommandText.field("Account", CommandText.account(account)))
                        .append(CommandText.field("Owner", CommandText.value(owner.name())))
                        .append(CommandText.field("Balance", CommandText.amount(currency.formatValueComponent(account.balance(), true)))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int requestDeletion(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount account = AccountArgument.getAccount(data, owner.id(), rawId);
        if (data.isDefaultAccount(owner.id(), account.getId())) {
            source.sendFailure(CommandText.error("The default account for this currency cannot be deleted. Choose another default first."));
            return 0;
        }
        source.sendFailure(CommandText.confirmation(
                CommandText.account(account),
                CommandText.field("Owner", CommandText.value(owner.name()))
                        .append(CommandText.field("Balance to be lost", CommandText.amount(account.formattedBalance()))),
                deletionCommand(context, owner, account.getId())
        ));
        return 0;
    }

    private int deleteAccount(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount account = AccountArgument.getAccount(data, owner.id(), rawId);
        var deleted = CommandResult.require(data.deleteAccount(owner.id(), account.getId()));

        source.sendSuccess(() -> CommandText.success("Deleted account ").append(CommandText.value(deleted.getId()))
                .append(" for ").append(CommandText.value(owner.name())).append("."), AccountArgument.isActingAs(context));
        return 1;
    }

    private int queryName(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        source.sendSuccess(
                () -> CommandText.property(CommandText.account(selection.account()), "Name", CommandText.value(selection.account().name())),
                false
        );
        return 1;
    }

    private int setName(CommandContext<CommandSourceStack> context, @Nullable String rawId, String value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        var updated = CommandResult.require(selection.data().updateAccount(
                selection.owner().id(), account.getId(), value, account.iconItem(), account.currencyId()
        ));
        source.sendSuccess(
                () -> CommandText.updated(CommandText.account(updated), "Name", CommandText.value(value))
                        .append(CommandText.field("Owner", CommandText.value(selection.owner().name()))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int queryIcon(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        Identifier iconId = BuiltInRegistries.ITEM.getKey(selection.account().iconItem());
        source.sendSuccess(
                () -> CommandText.property(CommandText.account(selection.account()), "Icon", CommandText.value(iconId)),
                false
        );
        return 1;
    }

    private int setIcon(CommandContext<CommandSourceStack> context, @Nullable String rawId, Item value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        var updated = CommandResult.require(selection.data().updateAccount(
                selection.owner().id(), account.getId(), account.displayName(), value, account.currencyId()
        ));
        Identifier iconId = BuiltInRegistries.ITEM.getKey(value);
        source.sendSuccess(
                () -> CommandText.updated(CommandText.account(updated), "Icon", CommandText.value(iconId))
                        .append(CommandText.field("Owner", CommandText.value(selection.owner().name()))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int queryCurrency(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        var currency = CommandResult.require(selection.account().getCurrency());
        source.sendSuccess(
                () -> CommandText.property(CommandText.account(selection.account()), "Currency", CommandText.currency(currency)),
                false
        );
        return 1;
    }

    private int setCurrency(CommandContext<CommandSourceStack> context, @Nullable String rawId, String rawCurrencyId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(selection.data(), rawCurrencyId);

        var updated = CommandResult.require(selection.data().updateAccount(
                selection.owner().id(), account.getId(), account.displayName(), account.iconItem(), currency.getId()
        ));
        source.sendSuccess(
                () -> CommandText.updated(CommandText.account(updated), "Currency", CommandText.currency(currency))
                        .append(CommandText.field("Owner", CommandText.value(selection.owner().name()))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private AccountSelection findAccount(
            CommandContext<CommandSourceStack> context, @Nullable String rawId
    ) throws CommandSyntaxException {
        GameProfile owner = AccountArgument.getOwner(context);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        return new AccountSelection(owner, data, AccountArgument.getAccount(data, owner.id(), rawId));
    }

    private String deletionCommand(CommandContext<CommandSourceStack> context, GameProfile owner, String id) {
        String prefix = !AccountArgument.isActingAs(context)
                ? "/" + PolyCoin.MOD_ID + " account"
                : "/" + PolyCoin.MOD_ID + " as " + owner.name() + " account";
        return prefix + " delete \"" + id + "\" confirm";
    }

    private record AccountSelection(
            GameProfile owner,
            PolyCoinEconomyData data,
            PolyCoinEconomyAccount account
    ) {}
}
