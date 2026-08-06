package de.erethon.mccinema.audio;

/** Pure geometry used by the server-authoritative audio radius filter. */
public record ScreenAudioBounds(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {

    public static ScreenAudioBounds of(double x, double y, double z, String facing,
                                       int mapWidth, int mapHeight) {
        double x2 = x;
        double z2 = z;
        switch (facing == null ? "" : facing) {
            case "NORTH" -> x2 = x + mapWidth;
            case "SOUTH" -> x2 = x - mapWidth;
            case "EAST" -> z2 = z + mapWidth;
            case "WEST" -> z2 = z - mapWidth;
            default -> {
            }
        }
        return new ScreenAudioBounds(Math.min(x, x2), y, Math.min(z, z2),
            Math.max(x, x2), y + mapHeight, Math.max(z, z2));
    }

    public double distanceSquared(double x, double y, double z) {
        double dx = axisDistance(x, minX, maxX);
        double dy = axisDistance(y, minY, maxY);
        double dz = axisDistance(z, minZ, maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean containsWithinRadius(double x, double y, double z, double radius) {
        return distanceSquared(x, y, z) <= radius * radius;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }
}
