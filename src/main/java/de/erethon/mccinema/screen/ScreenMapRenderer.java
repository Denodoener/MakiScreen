/*
 * Reconstructed from the official MCCinema 2.3.3 release artifact because
 * this production source was missing from the upstream 2.3.3 tag.
 * MCCinema is licensed under GPL-3.0; see the repository LICENSE file.
 */
package de.erethon.mccinema.screen;

import de.erethon.mccinema.screen.MapTile;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

public class ScreenMapRenderer extends MapRenderer {
    private final MapTile tile;
    private byte[] lastRenderedData;

    public ScreenMapRenderer(MapTile tile) {
        super(false);
        this.tile = tile;
    }

    public void render(@NotNull MapView mapView, @NotNull MapCanvas canvas, @NotNull Player player) {
        byte[] data = this.tile.getLastFrameData();
        if (data == null || data.length != 16384 || data == this.lastRenderedData) {
            return;
        }
        for (int y = 0; y < 128; ++y) {
            int rowOffset = y * 128;
            for (int x = 0; x < 128; ++x) {
                canvas.setPixel(x, y, data[rowOffset + x]);
            }
        }
        this.lastRenderedData = data;
    }
}
