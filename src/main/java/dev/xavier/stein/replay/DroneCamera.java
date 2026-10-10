/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import static dev.xavier.stein.replay.Interp.sq;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * The replay's drone camera: parked at a point and always looking the same way (behind you and from
 * above), it only slides to follow when you leave the slack around the center of the frame, never
 * letting you out of it, and it sees through blocks like the spectator. The camera is an entity of
 * its own, set as the game's view entity; the game interpolates its position between one tick and
 * the next, so each tick it gets where it is now (prev) and the next step (pos), and the frame is
 * smooth at any speed.
 */
final class DroneCamera {

    /**
     * The drone: distance, tilt (degrees down), the sides it tries (degrees from your back), the
     * slack around the center before it follows (sideways and in height), how much of what is left
     * it moves per tick, and how far you may get from the center (from there it goes along at once:
     * you never leave the frame).
     */
    private static final double DRONE_DIST = 6.5;

    private static final float DRONE_PITCH = 28;
    private static final float[] DRONE_SIDES = {25, -25, 65, -65, 110, -110, 160, -160};
    private static final double DEAD_H = 1.0;
    private static final double DEAD_V = 0.6;
    private static final double FOLLOW = 0.3;
    private static final double MAX_H = 2.0;
    private static final double MAX_V = 1.2;

    /** The camera's eye height (it is a player without a body). */
    private static final double EYE = 1.62;

    private final Minecraft mc;
    final EntityOtherPlayerMP cam;

    /** The point the drone follows (you, when you leave the slack), its fixed direction. */
    private double ax;

    private double ay;
    private double az;
    private float droneYaw;
    private float dronePitch;
    private boolean droneChosen;

    /**
     * The current distance (shorter than the drone's only when it enters a wall to see through).
     */
    private double camDist = DRONE_DIST;

    DroneCamera(Minecraft mc) {
        this.mc = mc;
        this.cam = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
    }

    /**
     * Follows you at (px, py, pz), your feet, with your yaw for the first choice of side. {@code
     * jump}: a new position without sliding (start, loop, jump in time). A block in the way does
     * not move it: it sees through, like the spectator.
     */
    void update(double px, double footY, double pz, float yaw, boolean jump) {
        double py = footY + 1.0;
        if (!droneChosen) {
            choose(px, py, pz, yaw);
        }
        if (jump) {
            ax = px;
            ay = py;
            az = pz;
        } else {
            follow(px, py, pz);
        }
        // Like the spectator: inside a block the game draws seeing through it (in a hole, the
        // camera stays on the ground and sees you falling in). In the air, with a block between it
        // and you (a wall), it enters the first block on the way, and the wall goes away the same.
        double[] look = Interp.look(droneYaw, dronePitch);
        double want = DRONE_DIST;
        double ex = ax - look[0] * DRONE_DIST;
        double ey = ay - look[1] * DRONE_DIST;
        double ez = az - look[2] * DRONE_DIST;
        if (!solid(ex, ey, ez)) {
            MovingObjectPosition hit =
                    mc.theWorld.rayTraceBlocks(
                            new Vec3(ex, ey, ez), new Vec3(px, py, pz), false, true, false);
            if (hit != null
                    && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.hitVec != null) {
                want =
                        Math.max(
                                0.6,
                                Math.sqrt(
                                                sq(hit.hitVec.xCoord - ax)
                                                        + sq(hit.hitVec.yCoord - ay)
                                                        + sq(hit.hitVec.zCoord - az))
                                        - 0.4);
            }
        }
        camDist = jump ? want : camDist + (want - camDist) * (want < camDist ? 0.5 : 0.15);
        double cx = ax - look[0] * camDist;
        double cy = ay - look[1] * camDist - EYE;
        double cz = az - look[2] * camDist;
        if (jump) {
            cam.prevPosX = cam.lastTickPosX = cx;
            cam.prevPosY = cam.lastTickPosY = cy;
            cam.prevPosZ = cam.lastTickPosZ = cz;
        } else {
            cam.prevPosX = cam.lastTickPosX = cam.posX;
            cam.prevPosY = cam.lastTickPosY = cam.posY;
            cam.prevPosZ = cam.lastTickPosZ = cam.posZ;
        }
        cam.posX = cx;
        cam.posY = cy;
        cam.posZ = cz;
        cam.prevRotationYaw = cam.rotationYaw = droneYaw;
        cam.prevRotationPitch = cam.rotationPitch = dronePitch;
    }

    /** Slides toward you once you leave the slack, and never lets you out of the frame. */
    private void follow(double px, double py, double pz) {
        double dx = px - ax;
        double dz = pz - az;
        double h = Math.sqrt(dx * dx + dz * dz);
        double tx = h > DEAD_H ? ax + dx * (1 - DEAD_H / h) : ax;
        double tz = h > DEAD_H ? az + dz * (1 - DEAD_H / h) : az;
        double dy = py - ay;
        double ty = Math.abs(dy) > DEAD_V ? ay + dy - Math.signum(dy) * DEAD_V : ay;
        ax += (tx - ax) * FOLLOW;
        ay += (ty - ay) * FOLLOW;
        az += (tz - az) * FOLLOW;
        dx = px - ax;
        dz = pz - az;
        h = Math.sqrt(dx * dx + dz * dz);
        if (h > MAX_H) {
            ax = px - dx * MAX_H / h;
            az = pz - dz * MAX_H / h;
        }
        if (Math.abs(py - ay) > MAX_V) {
            ay = py - Math.signum(py - ay) * MAX_V;
        }
    }

    /**
     * The drone's direction, chosen once: behind you and a little to the side, looking from above;
     * the first side with a clear path to you. Surrounded (a hole, a pit), higher up, seeing
     * through the ground.
     */
    private void choose(double px, double py, double pz, float yaw) {
        droneChosen = true;
        for (float off : DRONE_SIDES) {
            double[] look = Interp.look(yaw + off, DRONE_PITCH);
            double[] p = {
                px - look[0] * DRONE_DIST, py - look[1] * DRONE_DIST, pz - look[2] * DRONE_DIST
            };
            if (clear(px, py, pz, p)) {
                droneYaw = yaw + off;
                dronePitch = DRONE_PITCH;
                return;
            }
        }
        droneYaw = yaw;
        dronePitch = 55;
    }

    /** A block that blocks the view at the point (the camera inside it sees through). */
    private boolean solid(double x, double y, double z) {
        return mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock().isOpaqueCube();
    }

    /** No block between the target and the point. */
    private boolean clear(double tx, double ty, double tz, double[] p) {
        return free(tx, ty, tz, p)
                >= Math.sqrt(sq(p[0] - tx) + sq(p[1] - ty) + sq(p[2] - tz)) - 1e-3;
    }

    /** How far from the target toward the point before hitting a block (all of it, without one). */
    private double free(double tx, double ty, double tz, double[] p) {
        double full = Math.sqrt(sq(p[0] - tx) + sq(p[1] - ty) + sq(p[2] - tz));
        MovingObjectPosition hit =
                mc.theWorld.rayTraceBlocks(
                        new Vec3(tx, ty, tz), new Vec3(p[0], p[1], p[2]), false, true, false);
        if (hit == null
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.hitVec == null) {
            return full;
        }
        return Math.sqrt(
                sq(hit.hitVec.xCoord - tx)
                        + sq(hit.hitVec.yCoord - ty)
                        + sq(hit.hitVec.zCoord - tz));
    }
}
