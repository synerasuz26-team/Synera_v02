package com.synera.api.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Explicit CLI-only maintenance. No HTTP endpoint and no production execution. */
@Service
public class DemoTenantCleanup {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public DemoTenantCleanup(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Transactional(readOnly=true)
    public Map<String,Long> plan() {
        Map<String,Long> result=new LinkedHashMap<>();
        result.put("tenant",count("tenant"));
        for(String table:tables())result.put(table,count(table));
        return result;
    }
    @Transactional
    public Map<String,Long> purge(Map<String,String> environment) {
        DatabaseTaskGuard.validate(environment,"purge:demo");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('synera-demo-cleanup',0))");
        var codes=jdbc.queryForList("SELECT code FROM tenant WHERE is_demo ORDER BY code",String.class);
        Map<String,Long> result=new LinkedHashMap<>();
        if(codes.isEmpty()){result.put("tenant",0L);return result;}
        var tables=tables();var edges=foreignKeys();
        Set<String> scoped=new HashSet<>(tables);scoped.add("tenant");
        // Refuse mixed real/demo references in either direction, before changing any row.
        for(var edge:edges)if(scoped.contains(edge.child)&&scoped.contains(edge.parent)) {
            String sql="SELECT EXISTS(SELECT 1 FROM "+table(edge.child)+" c JOIN "+table(edge.parent)+" p ON "+edge.join
                    +" WHERE ("+candidate(edge.child,"c")+")<>("+candidate(edge.parent,"p")+"))";
            if(Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class)))
                throw new IllegalStateException("Từ chối xóa demo: có tham chiếu chéo với tenant thật giữa "+edge.child+" và "+edge.parent+".");
        }
        for(String name:tables) {
            var polymorphic=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name IN ('scope_id','subject_id')",String.class,name);
            for(String column:polymorphic) {
                String targets="SELECT id FROM tenant WHERE is_demo UNION SELECT id FROM outlet WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo) UNION SELECT id FROM org_unit WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo) UNION SELECT id FROM route WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo) UNION SELECT id FROM app_users WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo)";
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table(name)+" WHERE tenant_id NOT IN (SELECT id FROM tenant WHERE is_demo) AND "+identifier(column)+" IN ("+targets+"))",Boolean.class)))
                    throw new IllegalStateException("Từ chối xóa demo: phạm vi đa hình của dữ liệu thật tham chiếu demo trong "+name+".");
            }
        }
        result.put("auth_login_attempt",(long)jdbc.update("DELETE FROM auth_login_attempt WHERE email IN (SELECT email FROM app_users WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo))"));
        result.put("spring_session",(long)jdbc.update("DELETE FROM spring_session WHERE principal_name IN (SELECT email FROM app_users WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo))"));
        // Closed demo periods are deliberately opened only inside this purge transaction.
        jdbc.update("UPDATE period SET status='OPEN' WHERE tenant_id IN (SELECT id FROM tenant WHERE is_demo)");
        var selfRefs=jdbc.queryForList("""
                SELECT DISTINCT c.relname AS table_name,a.attname AS column_name
                FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=ANY(f.conkey)
                WHERE f.contype='f' AND f.conrelid=f.confrelid AND n.nspname='public' AND NOT a.attnotnull
                """);
        for(var ref:selfRefs)if(scoped.contains(ref.get("table_name").toString()))jdbc.update("UPDATE "+table(ref.get("table_name").toString())+" c SET "+identifier(ref.get("column_name").toString())+"=NULL WHERE "+candidate(ref.get("table_name").toString(),"c"));
        for(int pass=0;pass<tables.size()+2;pass++) {
            long removed=0;
            for(String name:tables) {
                StringBuilder sql=new StringBuilder("DELETE FROM "+table(name)+" p WHERE "+candidate(name,"p"));
                for(var edge:edges)if(edge.parent.equals(name))sql.append(" AND NOT EXISTS(SELECT 1 FROM ").append(table(edge.child)).append(" c WHERE ").append(edge.join).append(")");
                int count=jdbc.update(sql.toString());removed+=count;result.merge(name,(long)count,Long::sum);
            }
            if(removed==0)break;
        }
        for(String name:tables)if(count(name)>0)throw new IllegalStateException("Không thể xóa toàn bộ demo do ràng buộc tại "+name+". Đã hoàn tác toàn bộ.");
        result.put("tenant",(long)jdbc.update("DELETE FROM tenant WHERE is_demo"));
        try {
            jdbc.update("INSERT INTO demo_cleanup_log(id,tenant_codes,row_counts,requested_by) VALUES (?,?::jsonb,?::jsonb,?)",UUID.randomUUID(),json.writeValueAsString(codes),json.writeValueAsString(result),System.getProperty("user.name","CLI"));
        }catch(java.io.IOException exception){throw new IllegalStateException(exception);}
        return result;
    }
    private List<String> tables(){return jdbc.queryForList("SELECT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name='tenant_id' ORDER BY table_name",String.class);}
    private long count(String name){Long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table(name)+" p WHERE "+candidate(name,"p"),Long.class);return count==null?0:count;}
    private List<Edge> foreignKeys(){return jdbc.query("""
            SELECT child.relname,parent.relname,
              string_agg(format('c.%I=p.%I',ca.attname,pa.attname),' AND ' ORDER BY keys.ordinality) AS join_sql
            FROM pg_constraint f JOIN pg_class child ON child.oid=f.conrelid
            JOIN pg_class parent ON parent.oid=f.confrelid
            JOIN pg_namespace n ON n.oid=child.relnamespace
            JOIN LATERAL unnest(f.conkey,f.confkey) WITH ORDINALITY AS keys(child_key,parent_key,ordinality) ON TRUE
            JOIN pg_attribute ca ON ca.attrelid=child.oid AND ca.attnum=keys.child_key
            JOIN pg_attribute pa ON pa.attrelid=parent.oid AND pa.attnum=keys.parent_key
            WHERE f.contype='f' AND n.nspname='public' GROUP BY f.oid,child.relname,parent.relname
            """,(rs,index)->new Edge(rs.getString(1),rs.getString(2),rs.getString(3)));}
    private static String candidate(String table,String alias){return alias+(table.equals("tenant")?".id":".tenant_id")+" IN (SELECT id FROM tenant WHERE is_demo)";}
    private static String table(String value){return "public."+identifier(value);}
    private static String identifier(String value){if(!value.matches("[a-z_][a-z0-9_]*"))throw new IllegalStateException("Định danh schema không được hỗ trợ.");return '"'+value+'"';}
    private record Edge(String child,String parent,String join){}
}
