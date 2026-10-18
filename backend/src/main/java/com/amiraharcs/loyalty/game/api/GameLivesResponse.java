package com.amiraharcs.loyalty.game.api;

import java.time.Instant;

public record GameLivesResponse(int availableLives, int maxLives, Instant nextRechargeAt, Instant serverTime) { }
