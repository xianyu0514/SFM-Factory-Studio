package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Login-time capability announcement sent by this addon's server side
 * (server → client), e.g. ["with_component"]. The client-side handler only
 * flips {@link SfmCaps}; vanilla-SFM servers never send it and the editor
 * keeps the gated features hidden.
 *
 * Wire format: varint count + utf strings.
 */
public class SfmCapabilitiesPayload {
    public final List<String> capabilities;

    public SfmCapabilitiesPayload(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public static void encode(SfmCapabilitiesPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.capabilities.size());
        for (String cap : msg.capabilities) buf.writeUtf(cap);
    }

    public static SfmCapabilitiesPayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> caps = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) caps.add(buf.readUtf());
        return new SfmCapabilitiesPayload(caps);
    }

    public static void handle(SfmCapabilitiesPayload msg, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                SfmCaps.accept(new HashSet<>(msg.capabilities)));
        ctx.get().setPacketHandled(true);
    }
}
