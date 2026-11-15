/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
 * Copyright (c) 2026 pjscruggs
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.raknetify.legacy;

import net.minecraftforge.fml.loading.LoadingModList;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Selects the palette repair only for the Forge mod pair with the affected chunk layout. */
public final class ChangedAntiXrayPlugin implements IMixinConfigPlugin {
    /** {@inheritDoc} */
    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return installed("changed", "0.15.7") && installed("antixray", "1.4.6");
    }

    /**
     * Checks Forge discovery metadata before either optional mod initializes.
     *
     * @param id Forge mod identifier
     * @param version affected mod version
     * @return whether the specified release was discovered
     */
    private static boolean installed(final String id, final String version) {
        return LoadingModList.get().getMods().stream()
                .anyMatch(mod ->
                        id.equals(mod.getModId()) && version.equals(mod.getVersion().toString()));
    }

    /** {@inheritDoc} */
    @Override
    public void onLoad(final String mixinPackage) {}

    /** {@inheritDoc} */
    @Override
    public @Nullable String getRefMapperConfig() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void acceptTargets(final Set<String> ownTargets, final Set<String> otherTargets) {}

    /** {@inheritDoc} */
    @Override
    public List<String> getMixins() {
        return List.of();
    }

    /** {@inheritDoc} */
    @Override
    public void preApply(
            final String target, final ClassNode node, final String name, final IMixinInfo info) {}

    /** {@inheritDoc} */
    @Override
    public void postApply(
            final String target, final ClassNode node, final String name, final IMixinInfo info) {}
}
