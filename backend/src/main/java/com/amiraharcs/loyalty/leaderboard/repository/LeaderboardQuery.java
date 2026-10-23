package com.amiraharcs.loyalty.leaderboard.repository;

import com.amiraharcs.loyalty.leaderboard.service.LeaderboardCursor.Position;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

/** One SQL statement gives page + global own rank the same PostgreSQL snapshot. */
@Repository
public class LeaderboardQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public LeaderboardQuery(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(boolean own, long rank, UUID customerId, String alias, long score, Instant acceptedAt) {
        public Position position() { return new Position(score,acceptedAt,customerId); }
    }
    private static String ranked(boolean weekly) {
        return """
            WITH best AS (
                SELECT DISTINCT ON (s.customer_id) s.customer_id, g.score, g.accepted_at
                FROM loyalty.game_scores g
                JOIN loyalty.game_sessions s ON s.id=g.session_id AND s.status='COMPLETED'
                JOIN loyalty.customer_profiles p ON p.id=s.customer_id
                JOIN loyalty.users u ON u.id=p.user_id AND u.status='ACTIVE'
                WHERE EXISTS (SELECT 1 FROM loyalty.user_roles r WHERE r.user_id=u.id AND r.role_code='CUSTOMER')
            """+(weekly?"""
                AND s.weekly_period_id=:period
                AND NOT EXISTS (SELECT 1 FROM loyalty.user_roles r WHERE r.user_id=u.id AND r.role_code IN ('STAFF','ADMIN'))
            """:"")+"""
                ORDER BY s.customer_id, g.score DESC, g.accepted_at ASC
            ), ranked AS (
                SELECT row_number() OVER (ORDER BY score DESC,accepted_at ASC,customer_id ASC) AS rank, * FROM best
            )\n""";
    }
    /** Same authoritative eligibility/best-score/ranking CTE as customer pages. */
    public long participantCount(UUID periodId) {
        if(periodId==null) {throw new IllegalArgumentException("Weekly period required");}
        return jdbc.queryForObject(ranked(true)+" SELECT count(*) FROM ranked",new MapSqlParameterSource("period",periodId),Long.class);
    }
    public List<Row> read(UUID periodId,UUID customerId,int limit,Position after) {
        boolean weekly=periodId!=null;
        String sql=ranked(weekly)+"""
            , page AS (
                SELECT * FROM ranked
            """+(after==null?"":"""
                WHERE score < :score OR (score = :score AND accepted_at > :accepted)
                    OR (score = :score AND accepted_at = :accepted AND customer_id > :afterCustomer)
            """)+"""
                ORDER BY rank LIMIT :limit
            ), selected AS (
                SELECT false AS own, * FROM page
                UNION ALL
                SELECT true AS own, * FROM ranked WHERE customer_id=:customer
            )
            SELECT s.own,s.rank,s.customer_id,s.score,s.accepted_at,
                CASE WHEN p.leaderboard_private OR p.leaderboard_alias IS NULL
                    OR btrim(p.leaderboard_alias)='' OR position('@' in p.leaderboard_alias)>0
                    THEN 'Member' ELSE p.leaderboard_alias END AS alias
            FROM selected s JOIN loyalty.customer_profiles p ON p.id=s.customer_id
            ORDER BY s.own,s.rank
            """;
        var parameters=new MapSqlParameterSource().addValue("period",periodId).addValue("customer",customerId).addValue("limit",limit);
        if(after!=null) {parameters.addValue("score",after.score()).addValue("accepted",Timestamp.from(after.acceptedAt())).addValue("afterCustomer",after.customerId());}
        return jdbc.query(sql,parameters,(rs,n)->new Row(rs.getBoolean("own"),rs.getLong("rank"),rs.getObject("customer_id",UUID.class),
                rs.getString("alias"),rs.getLong("score"),rs.getTimestamp("accepted_at").toInstant()));
    }
}
