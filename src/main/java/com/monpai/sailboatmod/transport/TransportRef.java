package com.monpai.sailboatmod.transport;

import java.util.UUID;

/** Stable business identity; a provider's current physical assembly is a separate binding. */
public record TransportRef(String providerId, String dimensionId, UUID registrationUuid) {}
