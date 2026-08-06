package de.erethon.mccinema.video;

import de.erethon.mccinema.platform.PlayerPlatform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPacketDeliveryPlanTest {

    @Test
    void bedrockPathNeverUsesClientboundBundlePacket() {
        MapPacketDeliveryPlan.Plan plan = MapPacketDeliveryPlan.create(
            PlayerPlatform.BEDROCK_VIA_GEYSER, true, 10, false, 4);

        assertFalse(plan.usesBundle());
        assertEquals(MapPacketDeliveryPlan.Transport.INDIVIDUAL, plan.transport());
        assertEquals(3, plan.batches().size());
        assertTrue(plan.batches().stream().allMatch(batch -> batch.delayTicks() == 0L));
    }

    @Test
    void unknownPathAlsoAvoidsUnsafeJavaBundleFallback() {
        MapPacketDeliveryPlan.Plan plan = MapPacketDeliveryPlan.create(
            PlayerPlatform.UNKNOWN, true, 8, false, 4);

        assertFalse(plan.usesBundle());
    }

    @Test
    void javaPathMayContinueUsingBundles() {
        MapPacketDeliveryPlan.Plan plan = MapPacketDeliveryPlan.create(
            PlayerPlatform.JAVA, true, 10, false, 4);

        assertTrue(plan.usesBundle());
        assertEquals(List.of(new MapPacketDeliveryPlan.Batch(0, 10, 0L)), plan.batches());
    }

    @Test
    void largeBedrockFullFrameIsSpreadAcrossTicks() {
        MapPacketDeliveryPlan.Plan plan = MapPacketDeliveryPlan.create(
            PlayerPlatform.BEDROCK_VIA_GEYSER, true, 10, true, 4);

        assertEquals(List.of(
            new MapPacketDeliveryPlan.Batch(0, 4, 0L),
            new MapPacketDeliveryPlan.Batch(4, 8, 1L),
            new MapPacketDeliveryPlan.Batch(8, 10, 2L)
        ), plan.batches());
    }
}
