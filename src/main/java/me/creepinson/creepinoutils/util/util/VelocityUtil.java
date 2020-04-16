package me.creepinson.creepinoutils.util.util;

import me.creepinson.creepinoutils.api.util.math.Facing;
import me.creepinson.creepinoutils.api.util.math.Vector3;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;

public class VelocityUtil {
    public static final double MIN_SPEED = 0.1;

    public static void accelerate(Entity entity, Facing v) {
        accelerate(entity, v.getDirectionVec());
    }

    public static void accelerate(Entity entity, EnumFacing v) {
        accelerate(entity, Facing.values()[v.ordinal()]);
    }

    public static void accelerate(Entity entity, Vector3 v) {
        accelerate(entity, v, MIN_SPEED);
    }

    public static void accelerate(Entity entity, Facing v, double speed) {
        accelerate(entity, v.getDirectionVec(), speed);
    }

    public static void accelerate(Entity entity, EnumFacing v, double speed) {
        accelerate(entity, Facing.values()[v.ordinal()], speed);
    }

    public static void accelerate(Entity entity, Vector3 vector, double speed) {
        Vector3 v = vector.clone().mul(speed);
        entity.addVelocity(v.x, v.y, v.z);
    }

    public static Vector3 calculateParabolicVelocity(Vector3 from, Vector3 to, int heightGain) {
        // Gravity of a potion
        double gravity = 0.115;

        // Block locations
        int endGain = (int) (to.y - from.y);
        double horizDist = Math.sqrt(distanceSquared(from, to));

        // Height gain

        double maxGain = Math.max(heightGain, (endGain + heightGain));

        // Solve quadratic equation for velocity
        double a = -horizDist * horizDist / (4 * maxGain);
        double b = horizDist;
        double c = -endGain;

        double slope = -b / (2 * a) - Math.sqrt(b * b - 4 * a * c) / (2 * a);

        // Vertical velocity
        double vy = Math.sqrt(maxGain * gravity);

        // Horizontal velocity
        double vh = vy / slope;

        // Calculate horizontal direction
        int dx = (int) (to.x - from.x);
        int dz = (int) (to.z - from.z);
        double mag = Math.sqrt(dx * dx + dz * dz);
        double dirx = dx / mag;
        double dirz = dz / mag;

        // Horizontal velocity components
        double vx = vh * dirx;
        double vz = vh * dirz;

        return new Vector3(vx, vy, vz);
    }

    private static double distanceSquared(Vector3 from, Vector3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;

        return dx * dx + dz * dz;
    }
}