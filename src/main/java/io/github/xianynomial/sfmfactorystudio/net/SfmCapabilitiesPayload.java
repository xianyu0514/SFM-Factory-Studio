package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Login-time capability announcement sent by the SFM fork (server → client),
 * e.g. ["with_component"]. Both sides register this as optional: vanilla SFM
 * servers never send it and the editor keeps the gated features hidden.
 *
 * Wire format: varint count + utf strings — the fork must match exactly.
 */
public record SfmCapabilitiesPayload(List<String> capabilities) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SfmCapabilitiesPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "sfm_capabilities"));

    public static final StreamCodec<FriendlyByteBuf, SfmCapabilitiesPayload> CODEC = CustomPacketPayload.codec(
            SfmCapabilitiesPayload::write,
            SfmCapabilitiesPayload::read);

    private static SfmCapabilitiesPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> caps = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) caps.add(buf.readUtf());
        return new SfmCapabilitiesPayload(caps);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(capabilities.size());
        for (String cap : capabilities) buf.writeUtf(cap);
    }

    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (msg, ctx) -> ctx.enqueueWork(() ->
                SfmCaps.accept(new HashSet<>(msg.capabilities()))));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
