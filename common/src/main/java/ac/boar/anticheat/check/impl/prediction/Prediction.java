package ac.boar.anticheat.check.impl.prediction;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.check.api.BaseCheck;
import ac.boar.api.anticheat.annotations.CheckInfo;
import ac.boar.anticheat.check.api.impl.OffsetHandlerCheck;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.prediction.engine.data.VectorType;
import ac.boar.anticheat.util.MathUtil;
import ac.boar.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

import java.util.HashMap;
import java.util.Map;

@CheckInfo(name = "Prediction")
public class Prediction extends BaseCheck implements OffsetHandlerCheck {
    // Mispredictions have to persist for a few ticks before we alert. Actual movement cheats
    // (fly, speed, ...) mispredict every single tick so they still alert almost instantly, while
    // a single-tick edge case in the simulation only gets silently corrected by the rewind.
    private static final float BUFFER_LIMIT = 3.0F, BUFFER_DECAY = 0.25F;

    private final Map<String, BaseCheck> checks = new HashMap<>();

    public Prediction(BoarPlayer player) {
        super(player);

        this.checks.put("Phase", new BaseCheck(player, "Phase", "", false, BUFFER_LIMIT, BUFFER_DECAY));
        this.checks.put("Velocity", new BaseCheck(player, "Velocity", "", false, BUFFER_LIMIT, BUFFER_DECAY));

        this.checks.put("Strafe", new BaseCheck(player, "Strafe", "", false, BUFFER_LIMIT, BUFFER_DECAY));
        this.checks.put("Speed", new BaseCheck(player, "Speed", "", false, BUFFER_LIMIT, BUFFER_DECAY));
        this.checks.put("Flight", new BaseCheck(player, "Flight", "", false, BUFFER_LIMIT, BUFFER_DECAY));

        this.checks.put("Collisions", new BaseCheck(player, "Collisions", "", false, BUFFER_LIMIT, BUFFER_DECAY));
    }

    @Override
    public void onPredictionComplete(float offset) {
        if (player.tick < 10) {
            return;
        }

        if (offset < player.getMaxOffset()) {
            // Legit tick, slowly clear out evidence left behind by previous mispredictions.
            this.checks.values().forEach(BaseCheck::reward);
            return;
        }

        Boar.debug("[movement-debug] prediction offset tick=" + player.tick + " offset=" + offset + " max=" + player.getMaxOffset() + " alert=" + Boar.getConfig().alertThreshold() + " type=" + player.bestPossibility.getType() + " predictedPos=" + player.position + " actualPos=" + player.unvalidatedPosition + " predictedDelta=" + player.velocity + " actualDelta=" + player.unvalidatedTickEnd, Boar.DebugMessage.WARNING);

        if (!shouldDoFail() || offset < Boar.getConfig().alertThreshold()) {
            Boar.debug("[movement-debug] rewind reason=prediction-soft tick=" + player.tick + " offset=" + offset, Boar.DebugMessage.WARNING);
            player.getTeleportUtil().rewind(player.tick);
            return;
        }

        Boar.debug("[movement-debug] rewind reason=prediction-fail tick=" + player.tick + " offset=" + offset, Boar.DebugMessage.WARNING);
        player.getTeleportUtil().rewind(player.tick);

        boolean claimedHorizontal = player.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION);
        boolean claimedVertical = player.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION);
        if (claimedVertical != player.verticalCollision || claimedHorizontal != player.horizontalCollision) {
            fail("Phase", "o: " + offset + ", expect: (" + player.horizontalCollision + "," + player.verticalCollision + "), actual: (" + claimedHorizontal + "," + claimedVertical + ")");
        }

        if (player.bestPossibility.getType() == VectorType.VELOCITY) {
            fail("Velocity", "o: " + offset);
            return;
        }

        if (player.unvalidatedTickEnd.distanceTo(player.velocity) < player.getMaxOffset()) {
            fail("Collisions", "o: " + offset);
        }

        Vec3 actual = player.unvalidatedPosition.subtract(player.prevUnvalidatedPosition);
        Vec3 predicted = player.position.subtract(player.prevUnvalidatedPosition);
        if (!MathUtil.sameDirectionHorizontal(actual, predicted)) {
            fail("Strafe", "o: " + offset + ", expected direction: " + MathUtil.signAll(predicted).horizontalToString() + ", actual direction: " + MathUtil.signAll(actual).horizontalToString());
        }

        float squaredActual = actual.horizontalLengthSquared(), squaredPredicted = predicted.horizontalLengthSquared();
        if (actual.horizontalLengthSquared() > predicted.horizontalLengthSquared()) {
            fail("Speed", "o: " + offset + ", expected: " + squaredPredicted + ", actual: " + squaredActual);
        }

        if (Math.abs(player.position.y - player.unvalidatedPosition.y) > player.getMaxOffset()) {
            fail("Flight", "o: " + offset);
        }
    }

    public boolean shouldDoFail() {
        return player.tickSinceBlockResync <= 0 && !player.insideUnloadedChunk && !player.getTeleportUtil().isTeleporting() && player.sinceLoadingScreen > 5 && player.compensatedWorld.isChunkLoadedAt(player.position.x, player.position.z);
    }

    public void fail(String name, String verbose) {
        if (Boar.getConfig().disabledChecks().contains(name)) {
            return;
        }

        this.checks.get(name).fail(verbose);
    }
}
