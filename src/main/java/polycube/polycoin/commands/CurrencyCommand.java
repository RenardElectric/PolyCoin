package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;
import polycube.polycore.commands.CommandResult;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;

import java.math.BigInteger;

public final class CurrencyCommand extends PolyCommand {
    private static final String ID_ARGUMENT = "currency";

    public CurrencyCommand() {
        super(
                PolyCoin.MOD_ID,
                "currency",
                "Lists and inspects currencies; changing currencies and the default is admin-only",
                "list | info [id] | default [id] | create <id> <name> <denomination> <icon> <default_balance> | delete [id] [confirm] | modify [id] <name|denomination|icon|default_balance> [value]",
                PermissionLevel.ALL
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return super.getCommand(name, buildContext)
                .then(Commands.literal("list").executes(context -> listCurrencies(context.getSource())))
                .then(Commands.literal("info").executes(context -> showCurrencyInfo(context.getSource(), null)).then(
                        Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> showCurrencyInfo(
                                        context.getSource(),
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                ))
                .then(Commands.literal("default")
                        .executes(context -> defaultCurrency(context.getSource(), null))
                        .then(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .requires(this::canManageCurrencies)
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> defaultCurrency(context.getSource(), StringArgumentType.getString(context, ID_ARGUMENT)))
                        )
                )
                .then(createCommand(buildContext).requires(this::canManageCurrencies))
                .then(deleteCommand().requires(this::canManageCurrencies))
                .then(modifyCommand(buildContext).requires(this::canManageCurrencies));
    }

    private boolean canManageCurrencies(CommandSourceStack source) {
        return hasPermission(source, PermissionLevel.GAMEMASTERS);
    }

    private static int listCurrencies(CommandSourceStack source) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        var currencies = data.getCurrencies();
        var message = TextComponents.header("Currencies").append(TextComponents.muted(" (" + currencies.size() + ")"));

        if (currencies.isEmpty()) {
            message.append("\nNo currencies found.");
        } else {
            for (PolyCoinEconomyCurrency currency : currencies.values()) {
                message.append("\n  • ").append(CommandText.currency(currency));
                if (data.isDefaultCurrency(currency.getId())) {
                    message.append(TextComponents.badge());
                }
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int showCurrencyInfo(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        var statistics = CommandResult.require(data.getCurrencyStatistics(currency.getId()));

        var message = TextComponents.header("Currency details")
                .append(TextComponents.field("Name", TextComponents.value(currency.name())))
                .append(TextComponents.field("Denomination", TextComponents.value(currency.denomination())))
                .append(TextComponents.field("ID", TextComponents.value(currency.id())))
                .append(TextComponents.field("Icon", TextComponents.value(BuiltInRegistries.ITEM.getKey(currency.iconItem()))))
                .append(TextComponents.field("Starting balance", TextComponents.amount(currency.formatValueComponent(currency.defaultBalance(), true))))
                .append(TextComponents.field("Accounts", TextComponents.value(statistics.accountCount())))
                .append(TextComponents.field("Money in circulation", TextComponents.amount(currency.formatValueComponent(statistics.totalBalance(), true))))
                .append(TextComponents.field("Default currency", TextComponents.yesNo(data.isDefaultCurrency(currency.getId()))));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int defaultCurrency(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        if (rawId != null) CommandResult.require(data.setDefaultCurrency(currency.getId()));
        var message = (rawId == null ? TextComponents.header("Default currency") : TextComponents.success("Default currency updated"))
                .append(TextComponents.field("Currency", CommandText.currency(currency)));
        if (rawId != null) message.append("\nExisting accounts and balances are unchanged.");
        source.sendSuccess(() -> message, rawId != null);
        return 1;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createCommand(CommandBuildContext buildContext) {
        return Commands.literal("create").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.string()).then(
                        Commands.argument("name", StringArgumentType.string()).then(
                                Commands.argument("denomination", StringArgumentType.string()).then(
                                        Commands.argument("icon", ItemArgument.item(buildContext)).then(
                                                Commands.argument("default_balance", StringArgumentType.word())
                                                        .executes(context -> createCurrency(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, ID_ARGUMENT),
                                                                StringArgumentType.getString(context, "name"),
                                                                StringArgumentType.getString(context, "denomination"),
                                                                ItemArgument.getItem(context, "icon").item().value(),
                                                                StringArgumentType.getString(context, "default_balance")
                                                        ))
                                        )
                                )
                        )
                )
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> deleteCommand() {
        return deletionArguments(Commands.literal("delete"))
                .then(deletionArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> CurrencyArgument.suggestCurrencies(context, builder, "confirm"))));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T deletionArguments(T command) {
        return command.executes(context -> requestDeletion(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                .then(Commands.literal("confirm").executes(context ->
                        deleteCurrency(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> modifyCommand(CommandBuildContext buildContext) {
        return modificationArguments(Commands.literal("modify"), buildContext)
                .then(modificationArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> CurrencyArgument.suggestCurrencies(context, builder, "name", "denomination", "icon", "default_balance")), buildContext));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T modificationArguments(T command, CommandBuildContext buildContext) {
        return command
                .then(Commands.literal("name")
                        .executes(context -> queryName(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(context -> setName(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("denomination")
                        .executes(context -> queryDenomination(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(context -> setDenomination(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("icon")
                        .executes(context -> queryIcon(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", ItemArgument.item(buildContext))
                                .executes(context -> setIcon(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        ItemArgument.getItem(context, "value").item().value()))))
                .then(Commands.literal("default_balance")
                        .executes(context -> queryDefaultBalance(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(context -> setDefaultBalance(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))));
    }

    private static int createCurrency(
            CommandSourceStack source, String rawId,
            String name, String denomination, Item icon, String rawDefaultBalance
    ) throws CommandSyntaxException {
        String id = CurrencyArgument.parseId(rawId);
        BigInteger defaultBalance = AmountArgument.parse(rawDefaultBalance, true);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency created = CommandResult.require(data.createCurrency(id, name, denomination, icon, defaultBalance));

        source.sendSuccess(
                () -> TextComponents.success("Currency created")
                        .append(TextComponents.field("Currency", CommandText.currency(created)))
                        .append(TextComponents.field("Starting balance", TextComponents.amount(created.formatValueComponent(defaultBalance, true)))),
                true
        );
        return 1;
    }

    private static int requestDeletion(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        CommandResult.require(data.checkCurrencyDeletion(currency.getId()));
        int accountCount = CommandResult.require(data.countAccounts(currency.getId()));
        String id = currency.getId();
        source.sendFailure(TextComponents.confirmation(
                CommandText.currency(currency),
                TextComponents.field("Linked accounts to be deleted", TextComponents.value(accountCount))
                        .append("\nAll balances in these accounts will be lost."),
                "/" + PolyCoin.MOD_ID + " currency delete \"" + id + "\" confirm"
        ));
        return 0;
    }

    private static int deleteCurrency(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        var result = CommandResult.require(data.deleteCurrency(currency.getId()));

        source.sendSuccess(
                () -> TextComponents.success("Currency deleted")
                        .append(TextComponents.field("Currency", CommandText.currency(result.currency())))
                        .append(TextComponents.field("Accounts removed", TextComponents.value(result.deletedAccounts()))),
                true
        );
        return 1;
    }

    private static int queryName(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        source.sendSuccess(() -> TextComponents.property(CommandText.currency(currency), "Name", TextComponents.value(currency.name())), false);
        return 1;
    }

    private static int setName(CommandSourceStack source, @Nullable String rawId, String value) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        var updated = CommandResult.require(data.updateCurrency(currency.getId(), value, currency.denomination(), currency.iconItem(), currency.defaultBalance()));
        source.sendSuccess(() -> TextComponents.updated(CommandText.currency(updated), "Name", TextComponents.value(value)), true);
        return 1;
    }

    private static int queryDenomination(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        source.sendSuccess(() -> TextComponents.property(CommandText.currency(currency), "Denomination", TextComponents.value(currency.denomination())), false);
        return 1;
    }

    private static int setDenomination(CommandSourceStack source, @Nullable String rawId, String value) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        var updated = CommandResult.require(data.updateCurrency(currency.getId(), currency.displayName(), value, currency.iconItem(), currency.defaultBalance()));
        source.sendSuccess(() -> TextComponents.updated(CommandText.currency(updated), "Denomination", TextComponents.value(value)), true);
        return 1;
    }

    private static int queryIcon(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        Identifier itemId = BuiltInRegistries.ITEM.getKey(currency.iconItem());
        source.sendSuccess(() -> TextComponents.property(CommandText.currency(currency), "Icon", TextComponents.value(itemId)), false);
        return 1;
    }

    private static int setIcon(CommandSourceStack source, @Nullable String rawId, Item value) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        var updated = CommandResult.require(data.updateCurrency(currency.getId(), currency.displayName(), currency.denomination(), value, currency.defaultBalance()));
        Identifier itemId = BuiltInRegistries.ITEM.getKey(value);
        source.sendSuccess(() -> TextComponents.updated(CommandText.currency(updated), "Icon", TextComponents.value(itemId)), true);
        return 1;
    }

    private static int queryDefaultBalance(CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        source.sendSuccess(
                () -> TextComponents.property(CommandText.currency(currency), "Starting balance",
                        TextComponents.amount(currency.formatValueComponent(currency.defaultBalance(), true))),
                false
        );
        return 1;
    }

    private static int setDefaultBalance(CommandSourceStack source, @Nullable String rawId, String rawValue) throws CommandSyntaxException {
        BigInteger value = AmountArgument.parse(rawValue, true);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawId);
        PolyCoinEconomyCurrency updated = CommandResult.require(data.updateCurrency(
                currency.getId(), currency.displayName(), currency.denomination(), currency.iconItem(), value
        ));

        source.sendSuccess(
                () -> TextComponents.updated(CommandText.currency(updated), "Starting balance",
                        TextComponents.amount(updated.formatValueComponent(value, true)))
                        .append("\nExisting account balances are unchanged."),
                true
        );
        return 1;
    }

}
