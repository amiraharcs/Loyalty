package com.amiraharcs.loyalty.leaderboard.api;

/** No user/customer IDs or private profile fields cross this DTO boundary. */
public record LeaderboardEntry(Long rank, String alias, Long score, boolean isCurrentUser) { }
