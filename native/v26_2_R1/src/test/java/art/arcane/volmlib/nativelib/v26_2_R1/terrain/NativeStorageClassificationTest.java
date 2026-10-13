package art.arcane.volmlib.nativelib.v26_2_R1.terrain;

import art.arcane.volmlib.util.data.BSupport;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class NativeStorageClassificationTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void copperChestVariantsAcceptContainerLoot() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            BlockData air = blockData(Material.AIR);
            bukkit.when(() -> Bukkit.createBlockData(Material.AIR)).thenReturn(air);
            BSupport<Object> support = new BSupport<Object>() {
            };
            for (Material material : new Material[]{Material.COPPER_CHEST, Material.EXPOSED_COPPER_CHEST,
                    Material.WEATHERED_COPPER_CHEST, Material.OXIDIZED_COPPER_CHEST,
                    Material.WAXED_COPPER_CHEST, Material.WAXED_EXPOSED_COPPER_CHEST,
                    Material.WAXED_WEATHERED_COPPER_CHEST, Material.WAXED_OXIDIZED_COPPER_CHEST}) {
                BlockData chest = blockData(material);
                assertTrue(material.name(), support.isStorage(chest));
                assertTrue(material.name(), support.isStorageChest(chest));
            }
            assertFalse(support.isStorage(blockData(Material.COPPER_BLOCK)));
            assertFalse(support.isStorageChest(blockData(Material.COPPER_BLOCK)));
        }
    }

    private static BlockData blockData(Material material) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        return data;
    }
}
