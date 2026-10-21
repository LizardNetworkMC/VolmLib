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

package art.arcane.volmlib.nativelib.terrain.structure;

import art.arcane.volmlib.util.collection.KList;
import java.util.Collection;
import java.util.List;

/**
 * World-space axis-aligned bounds of one native structure piece. Volumes are resolved from seed, registry and
 * pack policy alone so the same query answers identically regardless of which chunks exist.
 */
public record NativeStructureVolume(
        String structure,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        SurfaceAdjustment surfaceAdjustment
) {
    // Shared empty sentinel handed to every engine and memoized by the volume caches; a silent
    // add()/clear() here would corrupt them all globally, so mutation fails loudly instead.
    public static final KList<NativeStructureVolume> NONE = new KList<>() {
        @Override
        public boolean add(NativeStructureVolume volume) {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }

        @Override
        public void add(int index, NativeStructureVolume volume) {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }

        @Override
        public boolean addAll(Collection<? extends NativeStructureVolume> volumes) {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }

        @Override
        public boolean addAll(int index, Collection<? extends NativeStructureVolume> volumes) {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }

        @Override
        public NativeStructureVolume remove(int index) {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }

        @Override
        public boolean remove(Object volume) {
            throw new UnsupportedOperationException("NativeStructureVolume.NONE is immutable");
        }
    };

    public static NativeStructureVolume of(String structure, int aX, int aY, int aZ, int bX, int bY, int bZ) {
        return new NativeStructureVolume(
                structure,
                Math.min(aX, bX),
                Math.min(aY, bY),
                Math.min(aZ, bZ),
                Math.max(aX, bX),
                Math.max(aY, bY),
                Math.max(aZ, bZ), null);
    }

    public boolean intersectsRect(int rectMinX, int rectMinZ, int rectMaxX, int rectMaxZ) {
        return maxX >= rectMinX && minX <= rectMaxX && maxZ >= rectMinZ && minZ <= rectMaxZ;
    }

    public boolean intersectsInfluenceRect(int rectMinX, int rectMinZ, int rectMaxX, int rectMaxZ) {
        if (intersectsRect(rectMinX, rectMinZ, rectMaxX, rectMaxZ)) {
            return true;
        }
        if (surfaceAdjustment != null) {
            int padding = surfaceAdjustment.horizontalPadding();
            for (SurfaceAnchor anchor : surfaceAdjustment.anchors()) {
                if ((long) anchor.maxX() + padding >= rectMinX && (long) anchor.minX() - padding <= rectMaxX
                        && (long) anchor.maxZ() + padding >= rectMinZ && (long) anchor.minZ() - padding <= rectMaxZ) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean intersects(int boxMinX, int boxMinY, int boxMinZ, int boxMaxX, int boxMaxY, int boxMaxZ) {
        return maxX >= boxMinX && minX <= boxMaxX
                && maxY >= boxMinY && minY <= boxMaxY
                && maxZ >= boxMinZ && minZ <= boxMaxZ;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean containsWithin(int x, int y, int z, int margin) {
        return x >= minX - margin && x <= maxX + margin
                && y >= minY - margin && y <= maxY + margin
                && z >= minZ - margin && z <= maxZ + margin;
    }

    public static int surfaceCutY(List<NativeStructureVolume> volumes, int x, int z, int originalY) {
        SurfaceAnchor selected = null;
        int selectedRange = 0;
        long weightedY = 0;
        long totalWeight = 0;
        long maximumInfluence = 0;
        for (NativeStructureVolume volume : volumes) {
            SurfaceAdjustment adjustment = volume.surfaceAdjustment();
            if (adjustment == null) {
                continue;
            }
            for (SurfaceAnchor anchor : adjustment.anchors()) {
                long outX = Math.max(0L, Math.max((long) anchor.minX() - x, (long) x - anchor.maxX()));
                long outZ = Math.max(0L, Math.max((long) anchor.minZ() - z, (long) z - anchor.maxZ()));
                boolean containsColumn = outX == 0 && outZ == 0;
                if (containsColumn && anchor.strength() > 1) {
                    if (selected == null || anchor.precedes(selected)) {
                        selected = anchor;
                        selectedRange = adjustment.range();
                    }
                    continue;
                }
                double distance = Math.sqrt((double) outX * outX + (double) outZ * outZ);
                if (!containsColumn && (adjustment.horizontalPadding() == 0 || distance >= adjustment.horizontalPadding())) {
                    continue;
                }
                double progress = containsColumn ? 0D : distance / adjustment.horizontalPadding();
                long influence = Math.round((1D - progress * progress * (3D - 2D * progress)) * 1_000_000L);
                long weight = influence * anchor.strength();
                int targetY = originalY + Math.max(-adjustment.range(), Math.min(adjustment.range(), anchor.groundY() - originalY));
                weightedY += weight * targetY;
                totalWeight += weight;
                maximumInfluence = Math.max(maximumInfluence, influence);
            }
        }
        if (selected != null) {
            return originalY + Math.max(-selectedRange, Math.min(selectedRange, selected.groundY() - originalY));
        }
        if (totalWeight == 0L) {
            return originalY;
        }
        double delta = (weightedY / (double) totalWeight - originalY) * (maximumInfluence / 1_000_000D);
        int magnitude = (int) Math.round(Math.abs(delta));
        return originalY + (delta < 0D ? -magnitude : magnitude);
    }

    public record SurfaceAdjustment(List<SurfaceAnchor> anchors, int horizontalPadding, int range) {
        public SurfaceAdjustment {
            anchors = List.copyOf(anchors);
        }
    }

    public record SurfaceAnchor(int minX, int maxX, int minZ, int maxZ, int groundY, int strength) {
        private boolean precedes(SurfaceAnchor other) {
            if (strength != other.strength) {
                return strength > other.strength;
            }
            if (groundY != other.groundY) {
                return groundY < other.groundY;
            }
            if (minX != other.minX) {
                return minX < other.minX;
            }
            if (minZ != other.minZ) {
                return minZ < other.minZ;
            }
            if (maxX != other.maxX) {
                return maxX < other.maxX;
            }
            return maxZ < other.maxZ;
        }
    }
}
