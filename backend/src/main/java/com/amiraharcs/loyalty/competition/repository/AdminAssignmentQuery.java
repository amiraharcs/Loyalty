package com.amiraharcs.loyalty.competition.repository;
import com.amiraharcs.loyalty.competition.api.AdminAssignmentResponse;
import com.amiraharcs.loyalty.competition.model.*;
import com.amiraharcs.loyalty.competition.service.PrizeAssignmentFilter;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
/** Static allowlisted predicates; never accepts column/order SQL from a request. */
@Repository
public class AdminAssignmentQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public AdminAssignmentQuery(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<AdminAssignmentResponse> list(PrizeAssignmentFilter filter,Instant now,boolean after,Instant time,UUID id,int limit) {
        String effective="CASE WHEN status='AVAILABLE' AND expires_at<=:now THEN 'EXPIRED' ELSE status END";
        StringBuilder sql=new StringBuilder("SELECT *, "+effective+" AS effective_status FROM loyalty.customer_prizes WHERE true");
        var p=new MapSqlParameterSource().addValue("now",Timestamp.from(now)).addValue("limit",limit);
        if(filter.customerId()!=null) {sql.append(" AND customer_id=:customer");p.addValue("customer",filter.customerId());}
        if(filter.prizeId()!=null) {sql.append(" AND prize_id=:prize");p.addValue("prize",filter.prizeId());}
        if(filter.status()!=null) {sql.append(" AND (").append(effective).append(")=:status");p.addValue("status",filter.status().name());}
        if(filter.triggerType()!=null) {sql.append(" AND trigger_type=:trigger");p.addValue("trigger",filter.triggerType().name());}
        if(filter.awardedFrom()!=null) {sql.append(" AND awarded_at>=:from");p.addValue("from",Timestamp.from(filter.awardedFrom()));}
        if(filter.awardedTo()!=null) {sql.append(" AND awarded_at<:to");p.addValue("to",Timestamp.from(filter.awardedTo()));}
        if(after) {sql.append(" AND (awarded_at<:time OR (awarded_at=:time AND id<:id))");p.addValue("time",Timestamp.from(time)).addValue("id",id);}
        sql.append(" ORDER BY awarded_at DESC,id DESC LIMIT :limit");
        return jdbc.query(sql.toString(),p,(rs,n)->new AdminAssignmentResponse(rs.getObject("id",UUID.class),rs.getObject("customer_id",UUID.class),
            rs.getObject("prize_id",UUID.class),rs.getObject("trigger_visit_id",UUID.class),rs.getString("prize_name_snapshot"),
            PrizeBenefitType.valueOf(rs.getString("benefit_type")),rs.getString("benefit_description_snapshot"),rs.getObject("benefit_value",Integer.class),
            rs.getObject("reward_id",UUID.class),rs.getString("reward_name_snapshot"),rs.getString("reward_description_snapshot"),
            CustomerPrizeStatus.valueOf(rs.getString("effective_status")),rs.getTimestamp("awarded_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),
            PrizeTriggerType.valueOf(rs.getString("trigger_type"))));
    }
}
