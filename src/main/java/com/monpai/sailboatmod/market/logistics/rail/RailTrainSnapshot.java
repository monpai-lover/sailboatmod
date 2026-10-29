package com.monpai.sailboatmod.market.logistics.rail;

import java.util.UUID;

/**
 * What the logistics map needs to know about one train, read from the rail mod (Create) once a second.
 * Kept free of the rail mod's own types so the logic around it can be tested without it.
 *
 * @param speed                 blocks per tick
 * @param stationName           the station the train stands at, or null
 * @param scheduled             the train runs a schedule or is navigating; others are not shown
 * @param destinationName       where it is heading, or null
 * @param distanceToDestination track distance left; with {@code distanceStartedAt} gives the leg's progress
 * @param signalWaitTicks       how long it has waited at a red signal
 */
public record RailTrainSnapshot(UUID trainId,
                                UUID ownerUuid,
                                String name,
                                double x,
                                double z,
                                double speed,
                                boolean derailed,
                                String stationName,
                                boolean scheduled,
                                String destinationName,
                                double destinationX,
                                double destinationZ,
                                double distanceToDestination,
                                double distanceStartedAt,
                                int signalWaitTicks) {
}
