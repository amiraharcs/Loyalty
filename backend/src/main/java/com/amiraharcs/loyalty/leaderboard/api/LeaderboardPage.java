package com.amiraharcs.loyalty.leaderboard.api;

import java.time.Instant;
import java.util.List;

public record LeaderboardPage(List<LeaderboardEntry> items, String nextCursor, boolean hasNext,
        LeaderboardEntry currentUser, WeeklyPeriodResponse period, Instant serverTime) { }
