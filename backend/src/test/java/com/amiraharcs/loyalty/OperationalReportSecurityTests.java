package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class OperationalReportSecurityTests extends OperationalReportTestSupport {
    @ParameterizedTest @ValueSource(strings={"overview","visits","points","redemptions","game","competition"})
    void adminGetsTypedPrivateAggregateWithoutMutations(String path) throws Exception {
        var admin=admin();var before=counts();var result=read(admin,path,200);String body=result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password","passwordHash","canonicalEmail","email","sessionId","customerId","userId","csrf","token","metadata","lookup","submittedScore");
        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.weekly_periods",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"overview","visits","points","redemptions","game","competition"})
    void allRoutesDenyUnauthenticated(String path) throws Exception {read(new Browser(),path,401);}
    @ParameterizedTest @CsvSource({"CUSTOMER,overview","CUSTOMER,visits","CUSTOMER,points","CUSTOMER,redemptions","CUSTOMER,game","CUSTOMER,competition","STAFF,overview","STAFF,visits","STAFF,points","STAFF,redemptions","STAFF,game","STAFF,competition"})
    void nonAdminRolesDenied(RoleCode role,String path) throws Exception {read(login(role),path,403);}
    @ParameterizedTest @ValueSource(strings={"points?from=private-invalid-value","points?from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z","points?from=2026-10-14T00:00:00Z&to=2026-10-14T00:00:00Z","points?from=2026-10-15T00:00:00Z&to=2026-10-14T00:00:00Z","visits?from=2026-01-01&to=2026-10-01","visits?from=2026-10-14&to=2026-10-14","visits?from=2026-02-30","visits?from=2026-10-14T00:00:00Z","overview?storeId=00000000-0000-0000-0000-000000000001","game?storeId=00000000-0000-0000-0000-000000000001","competition?storeId=00000000-0000-0000-0000-000000000001","visits?sort=password","points?cursor=opaque","points?from=&to=2026-10-14T00:00:00Z","game?from=one&from=two"})
    void malformedUnsupportedAndExcessiveFiltersAre400(String path) throws Exception {
        var result=read(admin(),path,400);assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-invalid-value");
    }
    @ParameterizedTest @ValueSource(strings={"visits","points","redemptions"})
    void unknownStoreIs404(String path) throws Exception {read(admin(),path+"?storeId="+UUID.randomUUID(),404);}
    @Test void disabledAdminSessionCannotReadReports() throws Exception {
        var admin=admin();jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",admin.userId);read(admin,"overview",401);
    }
}
