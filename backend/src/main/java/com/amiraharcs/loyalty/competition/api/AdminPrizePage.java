package com.amiraharcs.loyalty.competition.api;
import java.util.List;
public record AdminPrizePage<T>(List<T> items,String nextCursor,boolean hasNext) { }
