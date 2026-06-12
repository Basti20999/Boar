package ac.boar.protocol;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.geyser.GeyserBoar;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

@RequiredArgsConstructor
public class BoarHandlerAdaptor extends MessageToMessageCodec<BedrockPacketWrapper, BedrockPacketWrapper> {
    private final BoarPlayer player;
    private final BedrockPacketCodec codec;

    public static final String NAME = "boar-packet-handler";

    // An exception in a listener leaves the anticheat state half-updated (which can cause falses or
    // worse, silently stop checking the player), so don't hide it completely, log it (throttled).
    private static volatile long lastListenerError;

    private static void logListenerError(Exception e) {
        final long now = System.currentTimeMillis();
        if (now - lastListenerError < 1000L) {
            return;
        }
        lastListenerError = now;

        GeyserBoar.getLogger().error("Exception in a Boar packet listener, anticheat state might be de-synced!", e);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockPacketWrapper msg, List<Object> out) {
        if (player.isClosed()) {
            return;
        }

        final CloudburstPacketEvent event = new CloudburstPacketEvent(this.player, msg.getPacket());
        try {
            for (final PacketListener listener : PacketEvents.getApi().getListeners()) {
                listener.onPacketSend(event);
            }
        } catch (Exception e) {
            logListenerError(e);
        }

        if (event.isCancelled()) {
            return;
        }

        msg.setPacketBuffer(null);

        ByteBuf buf = ctx.alloc().buffer(128);
        try {
            BedrockPacket packet = event.getPacket();
            msg.setPacketId(this.codec.getPacketId(packet));
            this.codec.encodeHeader(buf, msg);
            this.codec.getCodec().tryEncode(this.codec.getHelper(), buf, packet);

            msg.setPacketBuffer(buf.retain());
            out.add(msg.retain());
        } catch (Exception ignored) {
        } finally {
            buf.release();
        }

        event.getPostTasks().forEach(Runnable::run);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockPacketWrapper msg, List<Object> out) {
        if (player.isClosed()) {
            return;
        }

        final CloudburstPacketEvent event = new CloudburstPacketEvent(this.player, msg.getPacket());
        try {
            for (final PacketListener listener : PacketEvents.getApi().getListeners()) {
                listener.onPacketReceived(event);
            }
        } catch (Exception e) {
            logListenerError(e);
        }

        if (event.isCancelled()) {
            return;
        }

        msg.setPacket(event.getPacket());
        out.add(msg.retain());

        event.getPostTasks().forEach(Runnable::run);
    }

}
