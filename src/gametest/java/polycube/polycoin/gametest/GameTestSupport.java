package polycube.polycoin.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec3;

public final class GameTestSupport {
    private GameTestSupport() {}

    @SuppressWarnings("removal")
    public static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setPos(helper.absoluteVec(new Vec3(1.5D, 2.0D, 1.5D)));
        return player;
    }

    public static int execute(GameTestHelper helper, ServerPlayer player, String command)
            throws CommandSyntaxException {
        return helper.getLevel().getServer().getCommands().getDispatcher()
                .execute(command, player.createCommandSourceStack());
    }

    public static int executeAsAdmin(GameTestHelper helper, ServerPlayer player, String command)
            throws CommandSyntaxException {
        return helper.getLevel().getServer().getCommands().getDispatcher()
                .execute(command, player.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS));
    }
}
