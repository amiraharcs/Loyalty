package com.amiraharcs.loyalty.game.api;

/** Stored along with its original HTTP status for exact idempotency replay. */
public record ScoreResult(int status,ScoreResponse body) { }
