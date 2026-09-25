package net.citizensnpcs.api.command;

/** Coordinate syntax independent of live dimension resolution. A null world means the command source's level. */
record CommandLocation(double x, double y, double z, float yaw, float pitch, String world) {
    static CommandLocation parse(String input) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("Missing location");
        boolean denizen = input.startsWith("l@");
        String value = denizen ? input.substring(2) : input;
        // Commas delimit native dimension IDs without splitting their namespace. Retain the historical all-colon
        // spelling for legacy bare world names; native IDs use the unambiguous comma form.
        String[] parts = value.split(value.contains(",") ? "," : ":", -1);
        if (parts.length < 3 || parts.length > 6)
            throw new IllegalArgumentException("Invalid location field count");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (parts[i].isEmpty())
                throw new IllegalArgumentException("Empty location field");
        }
        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        double z = Double.parseDouble(parts[2]);
        float yaw = 0, pitch = 0;
        String world = null;
        if (denizen && parts.length >= 5) {
            yaw = Float.parseFloat(parts[3]);
            pitch = Float.parseFloat(parts[4]);
            if (parts.length == 6) world = parts[5];
        } else {
            if (parts.length >= 4) world = parts[3];
            if (parts.length >= 5) yaw = Float.parseFloat(parts[4]);
            if (parts.length == 6) pitch = Float.parseFloat(parts[5]);
        }
        if (world != null && world.startsWith("w@")) world = world.substring(2);
        if (world != null && world.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Invalid location value");
        return new CommandLocation(x, y, z, yaw, pitch, world);
    }
}
