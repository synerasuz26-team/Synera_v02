package com.synera.api.operations;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.organization.BusinessAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;private final ActiveIdentity identity;private final BusinessAccessService access;
    public TaskService(JdbcTemplate jdbc,ActiveIdentity identity,BusinessAccessService access){this.jdbc=jdbc;this.identity=identity;this.access=access;}
    public ApiPage<TaskView> list(Jwt token,String status,int page,int size){
        Jwt jwt=identity.fresh(token);var actor=identity.require(jwt);var filter=filter(jwt,"task:view");
        List<Object> args=new ArrayList<>(filter.arguments());String where="t.deleted_at IS NULL AND "+filter.sql();
        if(status!=null&&!status.isBlank()){where+=" AND t.status=?";args.add(status.toUpperCase(Locale.ROOT));}
        long count=jdbc.queryForObject("SELECT count(*) FROM task t WHERE "+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        var rows=jdbc.query("SELECT t.* FROM task t WHERE "+where+" ORDER BY t.due_at NULLS LAST,t.id LIMIT ? OFFSET ?",(rs,row)->new TaskView(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("description"),rs.getString("status"),rs.getObject("assignee_id",UUID.class),rs.getObject("due_at",OffsetDateTime.class),rs.getLong("version")),args.toArray());
        return ApiPage.of(rows,page,size,count);
    }
    @Transactional public TaskView create(Jwt token,String title,String description,UUID assignee,OffsetDateTime due){
        Jwt jwt=identity.fresh(token);var actor=identity.require(jwt);access.resolve(jwt,"task:create");
        UUID target=assignee==null?actor.user():assignee;requireTarget(jwt,target);
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO task(id,tenant_id,title,description,created_by,assignee_id,due_at) VALUES(?,?,?,?,?,?,?)",id,actor.tenant(),title.strip(),description==null?"":description.strip(),actor.user(),target,due);
        return new TaskView(id,title.strip(),description==null?"":description.strip(),"OPEN",target,due,0);
    }
    @Transactional public int assign(Jwt token,UUID task,UUID assignee,long version){
        Jwt jwt=identity.fresh(token);identity.require(jwt);requireTarget(jwt,assignee);var scope=filter(jwt,"task:assign");
        List<Object> args=new ArrayList<>(List.of(assignee,task,version));args.addAll(scope.arguments());
        int n=jdbc.update("UPDATE task t SET assignee_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE t.id=? AND t.version=? AND t.deleted_at IS NULL AND "+scope.sql(),args.toArray());
        if(n!=1)throw new ApiProblem("TASK_VERSION_OR_SCOPE","Công việc ngoài phạm vi hoặc đã thay đổi.",HttpStatus.CONFLICT);return n;
    }
    @Transactional public int finish(Jwt token,UUID task,long version){
        Jwt jwt=identity.fresh(token);var actor=identity.require(jwt);access.resolve(jwt,"task:view");
        int n=jdbc.update("UPDATE task SET status='DONE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND version=? AND assignee_id=? AND deleted_at IS NULL AND status IN('OPEN','IN_PROGRESS')",actor.tenant(),task,version,actor.user());
        if(n!=1)throw new ApiProblem("TASK_VERSION_OR_SCOPE","Chỉ người được giao được hoàn tất việc đang mở và đúng phiên bản.",HttpStatus.CONFLICT);return n;
    }
    @Transactional public int followUp(Jwt token,UUID interaction,OffsetDateTime due){
        Jwt jwt=identity.fresh(token);var actor=identity.require(jwt);access.resolve(jwt,"interaction:log");
        int n=jdbc.update("UPDATE interaction SET follow_up_at=? WHERE id=? AND tenant_id=? AND actor_id=?",due,interaction,actor.tenant(),actor.user());
        if(n!=1)throw new AccessDeniedException("Chỉ được đặt lời hẹn cho tương tác do mình ghi trong tenant hiện tại.");return n;
    }
    private void requireTarget(Jwt jwt,UUID user){
        var actor=identity.require(jwt);Boolean active=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users WHERE id=? AND tenant_id=? AND status='ACTIVE')",Boolean.class,user,actor.tenant());
        if(!Boolean.TRUE.equals(active))throw new AccessDeniedException("Người nhận không hoạt động trong tenant này.");
        if(user.equals(actor.user()))return;
        var scope=access.resolve(jwt,"task:assign");
        if(scope.scope().equals("A"))return;
        if(scope.scope().equals("T")&&!scope.routeIds().isEmpty()){
            var args=new ArrayList<Object>(List.of(user,actor.tenant()));args.addAll(scope.routeIds());
            String marks=String.join(",",scope.routeIds().stream().map(id->"?").toList());
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_route WHERE user_id=? AND tenant_id=? AND route_id IN("+marks+"))",Boolean.class,args.toArray())))return;
        }
        if(scope.scope().equals("R")){
            var filter=access.orgUnitFilter(scope,"ou");var args=new ArrayList<Object>(List.of(user));args.addAll(filter.arguments());
            Boolean allowed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_org_unit uo JOIN org_unit ou ON ou.id=uo.org_unit_id AND ou.tenant_id=uo.tenant_id WHERE uo.user_id=? AND "+filter.sql()+")",Boolean.class,args.toArray());
            if(Boolean.TRUE.equals(allowed))return;
        }
        throw new AccessDeniedException("Người nhận ngoài phạm vi giao việc.");
    }
    private BusinessAccessService.Filter filter(Jwt jwt,String permission){
        var scope=access.resolve(jwt,permission);
        if(scope.scope().equals("A"))return new BusinessAccessService.Filter("t.tenant_id=?",List.of(scope.tenantId()));
        if(scope.scope().equals("T")&&!scope.routeIds().isEmpty()){
            var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.ownerId(),scope.ownerId()));args.addAll(scope.routeIds());
            String marks=String.join(",",scope.routeIds().stream().map(id->"?").toList());
            return new BusinessAccessService.Filter("t.tenant_id=? AND (t.created_by=? OR t.assignee_id=? OR EXISTS(SELECT 1 FROM user_route ur WHERE ur.user_id=t.assignee_id AND ur.tenant_id=t.tenant_id AND ur.route_id IN("+marks+")))",args);
        }
        if(scope.scope().equals("R")){
            var org=access.orgUnitFilter(scope,"ou");var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.ownerId(),scope.ownerId()));args.addAll(org.arguments());
            return new BusinessAccessService.Filter("t.tenant_id=? AND (t.created_by=? OR t.assignee_id=? OR EXISTS(SELECT 1 FROM user_org_unit uo JOIN org_unit ou ON ou.id=uo.org_unit_id AND ou.tenant_id=uo.tenant_id WHERE uo.user_id=t.assignee_id AND "+org.sql()+"))",args);
        }
        return new BusinessAccessService.Filter("t.tenant_id=? AND (t.created_by=? OR t.assignee_id=?)",List.of(scope.tenantId(),scope.ownerId(),scope.ownerId()));
    }
    public record TaskView(UUID id,String title,String description,String status,UUID assigneeId,OffsetDateTime dueAt,long version){}
}
