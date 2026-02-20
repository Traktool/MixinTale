package com.traktool.mixintale.bootstrap;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public final class MixinTaleMixinServiceBootstrap implements IMixinServiceBootstrap {
    @Override
    public String getName() {
        return "MixinTaleBootstrap";
    }

    @Override
    public String getServiceClassName() {
        return MixinTaleMixinService.class.getName();
    }

    @Override
    public void bootstrap() {
        // no-op
    }
}
