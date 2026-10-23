package com.amiraharcs.loyalty.admin.repository;

import com.amiraharcs.loyalty.admin.api.OperationalReportDtos.*;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.leaderboard.repository.LeaderboardQuery;
import com.amiraharcs.loyalty.competition.service.WeeklyPeriodService;
import com.amiraharcs.loyalty.loyalty.model.PointValues;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Read models only. Fixed SQL + bound values; no detail-history traversal or mutation services. */
@Repository
@Transactional(readOnly=true, propagation=Propagation.MANDATORY)
public class OperationalReportQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final LeaderboardQuery rankings;
    public OperationalReportQuery(NamedParameterJdbcTemplate jdbc,LeaderboardQuery rankings) {this.jdbc=jdbc;this.rankings=rankings;}
    private MapSqlParameterSource params(InstantRange range,UUID store,Instant now) {
        return new MapSqlParameterSource().addValue("from",boundary(range.from())).addValue("to",boundary(range.to()))
                .addValue("store",store).addValue("now",Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }
    // Stored facts have microsecond precision. Ceiling both half-open bounds preserves
    // exact comparisons for RFC3339 inputs with finer precision; JDBC rounds otherwise.
    private static Timestamp boundary(Instant value) {
        int remainder=value.getNano()%1000;
        return Timestamp.from(remainder==0?value:value.plusNanos(1000-remainder));
    }
    private static long points(BigDecimal value) {return PointValues.safeInteger(value.toBigIntegerExact());}
    private static long points(ResultSet row,String column) throws SQLException {return points(row.getBigDecimal(column));}
    public void requireStore(UUID id) {
        if(id!=null && Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM loyalty.stores WHERE id=:id)",new MapSqlParameterSource("id",id),Boolean.class))) {throw new ResourceNotFoundException();}
    }
    public Visits visits(DateRange range,UUID store) {
        var p=new MapSqlParameterSource().addValue("from",range.from()).addValue("to",range.to()).addValue("store",store);
        String where=" WHERE business_date>=:from AND business_date<:to"+(store==null?"":" AND store_id=:store");
        var totals=jdbc.query("SELECT count(*) AS total,count(DISTINCT customer_id) AS customers FROM loyalty.visits"+where,p,
                (rs,n)->new long[]{rs.getLong("total"),rs.getLong("customers")}).getFirst();
        var days=jdbc.query("SELECT business_date,count(*) AS total FROM loyalty.visits"+where+" GROUP BY business_date ORDER BY business_date",p,
                (rs,n)->new VisitDay(rs.getObject("business_date",LocalDate.class),rs.getLong("total")));
        var shops=jdbc.query("SELECT store_id,count(*) AS total FROM loyalty.visits"+where+" GROUP BY store_id ORDER BY store_id LIMIT 101",p,
                (rs,n)->new StoreVisits(rs.getObject("store_id",UUID.class),rs.getLong("total")));
        return new Visits(range,store,totals[0],totals[1],days,shops.stream().limit(100).toList(),shops.size()>100);
    }
    public Points points(InstantRange range,UUID store,Instant now) {
        String sql="SELECT t.type,CASE WHEN t.signed_amount>0 THEN 'POSITIVE' ELSE 'NEGATIVE' END AS sign,count(*) AS count,COALESCE(SUM(abs(t.signed_amount::numeric)),0) AS points FROM loyalty.point_transactions t ";
        if(store!=null) {sql+="LEFT JOIN loyalty.visits v ON v.id=t.visit_id LEFT JOIN loyalty.reward_redemptions r ON r.id=t.redemption_id ";}
        sql+="WHERE t.created_at>=:from AND t.created_at<:to"+(store==null?"":" AND ((t.type='EARN' AND v.store_id=:store) OR (t.type='REDEMPTION' AND r.store_id=:store))")
                +" GROUP BY t.type,sign";
        var rows=jdbc.query(sql,params(range,store,now),(rs,n)->new PointGroup(rs.getString("type"),rs.getString("sign"),new Amount(rs.getLong("count"),points(rs,"points"))));
        return new Points(range,store,amount(rows,"EARN","POSITIVE"),amount(rows,"REDEMPTION","NEGATIVE"),amount(rows,"REFUND","POSITIVE"),
                amount(rows,"ADJUSTMENT","POSITIVE"),amount(rows,"ADJUSTMENT","NEGATIVE"),store!=null);
    }
    private record PointGroup(String type,String sign,Amount value) { }
    private Amount amount(List<PointGroup> rows,String type,String sign) {return rows.stream().filter(r->r.type().equals(type)&&r.sign().equals(sign)).map(PointGroup::value).findFirst().orElse(new Amount(0,0));}
    public Redemptions redemptions(InstantRange range,UUID store,Instant now) {
        var p=params(range,store,now);String where=" WHERE requested_at>=:from AND requested_at<:to"+(store==null?"":" AND store_id=:store");
        var found=jdbc.query("SELECT CASE WHEN status='PENDING' AND expires_at<=:now THEN 'EXPIRED' ELSE status END AS state,count(*) AS total,SUM(point_cost_snapshot) AS points FROM loyalty.reward_redemptions"+where+" GROUP BY state",p,
                (rs,n)->new RedemptionState(rs.getString("state"),rs.getLong("total"),points(rs,"points")));
        var states=List.of("PENDING","FULFILLED","CANCELLED","EXPIRED").stream().map(status->found.stream().filter(r->r.status().equals(status)).findFirst().orElse(new RedemptionState(status,0,0))).toList();
        var shops=jdbc.query("SELECT store_id,count(*) AS total,SUM(point_cost_snapshot) AS points FROM loyalty.reward_redemptions"+where+" GROUP BY store_id ORDER BY store_id LIMIT 101",p,
                (rs,n)->new StoreRedemptions(rs.getObject("store_id",UUID.class),rs.getLong("total"),points(rs,"points")));
        return new Redemptions(range,now,store,states,shops.stream().limit(100).toList(),shops.size()>100);
    }
    public Game game(InstantRange range,Instant now) {
        var p=params(range,null,now);
        var cohort=jdbc.query("""
            SELECT count(*) AS total,count(*) FILTER(WHERE status='COMPLETED') AS completed,
                count(*) FILTER(WHERE status='EXPIRED' OR (status='ACTIVE' AND expires_at<=:now)) AS expired,
                count(*) FILTER(WHERE status='ACTIVE' AND started_at<=:now AND expires_at>:now) AS active,
                count(DISTINCT customer_id) AS players FROM loyalty.game_sessions WHERE started_at>=:from AND started_at<:to
            """,p,(rs,n)->new long[]{rs.getLong("total"),rs.getLong("completed"),rs.getLong("expired"),rs.getLong("active"),rs.getLong("players")}).getFirst();
        return jdbc.query("""
            SELECT count(*) AS total,round(avg(score),2) AS average,max(score) AS maximum,
                (SELECT count(*) FROM loyalty.game_sessions WHERE status='ACTIVE' AND started_at<=:now AND expires_at>:now) AS active
            FROM loyalty.game_scores WHERE accepted_at>=:from AND accepted_at<:to
            """,p,(rs,n)->new Game(range,now,cohort[0],cohort[1],cohort[2],cohort[3],cohort[4],rs.getLong("total"),rs.getBigDecimal("average"),
                    rs.getObject("maximum")==null?null:rs.getLong("maximum"),rs.getLong("active"))).getFirst();
    }
    public Week week(Instant now) {
        LocalDate monday=WeeklyPeriodService.startDate(now);
        var periods=jdbc.query("SELECT id,start_at,end_at,status FROM loyalty.weekly_periods WHERE start_date=:day",new MapSqlParameterSource("day",monday),
                (rs,n)->new Period(rs.getObject("id",UUID.class),rs.getTimestamp("start_at").toInstant(),rs.getTimestamp("end_at").toInstant(),rs.getString("status")));
        if(periods.isEmpty()) {return new Week(monday,WeeklyPeriodService.startAt(monday),WeeklyPeriodService.startAt(monday.plusDays(7)),"NOT_CREATED",false,0,0,List.of());}
        var period=periods.getFirst();
        long accepted=jdbc.queryForObject("SELECT count(*) FROM loyalty.game_scores g JOIN loyalty.game_sessions s ON s.id=g.session_id WHERE s.weekly_period_id=:id",new MapSqlParameterSource("id",period.id()),Long.class);
        var top=rankings.read(period.id(),null,5,null).stream().filter(row->!row.own()).map(row->new TopEntry(row.rank(),row.alias(),row.score())).toList();
        return new Week(monday,period.start(),period.end(),period.status(),true,rankings.participantCount(period.id()),accepted,top);
    }
    private record Period(UUID id,Instant start,Instant end,String status) { }
    public Competition competition(InstantRange range,Instant now) {
        var p=params(range,null,now);
        var statesFound=jdbc.query("SELECT CASE WHEN status='AVAILABLE' AND expires_at<=:now THEN 'EXPIRED' ELSE status END AS state,count(*) AS total FROM loyalty.customer_prizes WHERE awarded_at>=:from AND awarded_at<:to GROUP BY state",p,
                (rs,n)->new PrizeState(rs.getString("state"),rs.getLong("total")));
        var states=List.of("AVAILABLE","RESERVED","REDEEMED","EXPIRED","REVOKED").stream().map(state->statesFound.stream().filter(s->s.status().equals(state)).findFirst().orElse(new PrizeState(state,0))).toList();
        var triggersFound=jdbc.query("SELECT trigger_type,count(*) AS total FROM loyalty.customer_prizes WHERE awarded_at>=:from AND awarded_at<:to GROUP BY trigger_type",p,
                (rs,n)->new PrizeTrigger(rs.getString("trigger_type"),rs.getLong("total")));
        var triggers=List.of("WEEKLY_RANK","STREAK_MILESTONE").stream().map(trigger->triggersFound.stream().filter(t->t.trigger().equals(trigger)).findFirst().orElse(new PrizeTrigger(trigger,0))).toList();
        var definitions=jdbc.query("SELECT count(*) AS total,count(*) FILTER(WHERE active) AS active FROM loyalty.prizes WHERE trigger_type='WEEKLY_RANK'",p,
                (rs,n)->new long[]{rs.getLong("total"),rs.getLong("active")}).getFirst();
        return new Competition(range,now,week(now),definitions[0],definitions[1],states.stream().mapToLong(PrizeState::count).sum(),states,triggers,false);
    }
    public Overview overview(Instant now) {
        var date=now.atZone(WeeklyPeriodService.ZONE).toLocalDate();
        var p=new MapSqlParameterSource().addValue("day",date).addValue("start",Timestamp.from(date.atStartOfDay(WeeklyPeriodService.ZONE).toInstant())).addValue("now",Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        var rewards=jdbc.query("""
            SELECT count(*) FILTER(WHERE active AND (available_from IS NULL OR available_from<=:now) AND (available_until IS NULL OR available_until>:now)) AS available,
                count(*) FILTER(WHERE active AND available_from>:now) AS future,
                count(*) FILTER(WHERE NOT active) AS inactive,
                count(*) FILTER(WHERE active AND available_until<=:now) AS ended FROM loyalty.rewards
            """,p,(rs,n)->new Rewards(rs.getLong("available"),rs.getLong("future"),rs.getLong("inactive"),rs.getLong("ended"))).getFirst();
        Week current=week(now);
        return jdbc.query("""
            SELECT
              (SELECT count(*) FROM loyalty.users u JOIN loyalty.user_roles r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='CUSTOMER') AS customers,
              (SELECT count(*) FROM loyalty.users u JOIN loyalty.user_roles r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='STAFF') AS staff,
              (SELECT count(*) FROM loyalty.stores WHERE status='ACTIVE') AS stores,
              (SELECT count(*) FROM loyalty.visits WHERE business_date=:day AND confirmed_at<=:now) AS visits,
              (SELECT COALESCE(SUM(signed_amount),0) FROM loyalty.point_transactions WHERE type='EARN' AND created_at>=:start AND created_at<=:now) AS earned,
              (SELECT COALESCE(-SUM(signed_amount),0) FROM loyalty.point_transactions WHERE type='REDEMPTION' AND created_at>=:start AND created_at<=:now) AS redeemed,
              (SELECT count(*) FROM loyalty.reward_redemptions WHERE status='PENDING' AND requested_at<=:now AND expires_at>:now) AS pending,
              (SELECT count(*) FROM loyalty.reward_redemptions WHERE status='FULFILLED' AND fulfilled_at>=:start AND fulfilled_at<=:now) AS fulfilled,
              (SELECT count(*) FROM loyalty.game_sessions WHERE status='ACTIVE' AND started_at<=:now AND expires_at>:now) AS games,
              (SELECT count(*) FROM loyalty.game_scores WHERE accepted_at>=:start AND accepted_at<=:now) AS scores,
              (SELECT count(*) FROM loyalty.customer_prizes WHERE status='AVAILABLE' AND awarded_at<=:now AND expires_at>:now) AS prizes
            """,p,(rs,n)->new Overview(now,date,rs.getLong("customers"),rs.getLong("staff"),rs.getLong("stores"),rs.getLong("visits"),points(rs,"earned"),points(rs,"redeemed"),
                    rs.getLong("pending"),rs.getLong("fulfilled"),rewards,rs.getLong("games"),rs.getLong("scores"),rs.getLong("prizes"),current,false)).getFirst();
    }
}
