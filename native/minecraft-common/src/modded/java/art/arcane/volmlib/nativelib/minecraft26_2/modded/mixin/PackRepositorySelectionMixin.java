/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.volmlib.nativelib.minecraft26_2.modded.mixin;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeDatapackSource;
import art.arcane.volmlib.nativelib.modded.NativeMixinFlags;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;

@Mixin(PackRepository.class)
public abstract class PackRepositorySelectionMixin {
    @Inject(method = "rebuildSelected", at = @At("RETURN"), cancellable = true)
    private void volmlib$keepNativeDatapacksLast(Collection<String> selectedNames, CallbackInfoReturnable<List<Pack>> info) {
        NativeMixinFlags.markPackSelection();
        List<Pack> selected = info.getReturnValue();
        List<Pack> ordered = NativeDatapackSource.orderPinnedLast(selected, Pack::getId, NativeDatapackSource.pinnedIds());
        if (ordered != selected) {
            info.setReturnValue(List.copyOf(ordered));
        }
    }
}
