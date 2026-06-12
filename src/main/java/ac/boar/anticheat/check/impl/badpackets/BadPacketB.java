package ac.boar.anticheat.check.impl.badpackets;

import ac.boar.anticheat.check.api.annotations.CheckInfo;
import ac.boar.anticheat.check.api.impl.PacketCheck;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.protocol.api.CloudburstPacketEvent;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

@CheckInfo(name = "Bad Packet", type = "B")
public class BadPacketB extends PacketCheck {
    // While yaw wrapping is vanilla behaviour on Java, the Bedrock client always sends rotation
    // within the vanilla range. Boundary values (exactly +-180/+-90 plus floating point noise)
    // get the benefit of the doubt though, actual spoofed rotations are nowhere near the boundary.
    private static final float EPSILON = 1.0E-3F;
    private static final int KICK_VL = 3;

    public BadPacketB(BoarPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceived(CloudburstPacketEvent event) {
        if (!(event.getPacket() instanceof PlayerAuthInputPacket packet)) {
            return;
        }

        float claimedYaw = packet.getRotation().getY();
        float claimedPitch = packet.getRotation().getX();
        if (Math.abs(claimedYaw) > 180.0F + EPSILON) {
            fail("claimedYaw=" + claimedYaw);
        } else if (Math.abs(claimedPitch) > 90.0F + EPSILON) {
            fail("claimedPitch=" + claimedPitch);
        } else {
            return;
        }

        // Don't let the bogus rotation reach the rest of the pipeline or Geyser, but only kick once
        // we're sure this isn't a one-off, a false kick is the worst possible outcome.
        event.setCancelled(true);
        if (this.vl() >= KICK_VL) {
            player.kick("Invalid auth input packet!");
        }
    }
}
