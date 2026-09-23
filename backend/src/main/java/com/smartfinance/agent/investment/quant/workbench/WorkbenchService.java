package com.smartfinance.agent.investment.quant.workbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
public class WorkbenchService {
    final JdbcTemplate db;
    final ObjectMapper json;
    final TransactionTemplate tx;
    final WorkbenchTrackingIndex trackingIndex;
    final AnalysisServiceClient marketProvider;
    static final Set<String> OBJECTS = Set.of("universes", "factors", "strategies");
    static final Set<String> TASKS = Set.of("training-runs", "backtests", "factor-runs");
    static final int MINIMUM_EVALUATION_DAYS = 60;
    @Autowired
    public WorkbenchService(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager,
                            WorkbenchTrackingIndex trackingIndex, AnalysisServiceClient marketProvider) {
        this.db=db; this.json=json; this.tx=new TransactionTemplate(manager);
        this.trackingIndex=trackingIndex;
        this.marketProvider=marketProvider;
    }
    public WorkbenchService(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager,
                            WorkbenchTrackingIndex trackingIndex) {
        this(db,json,manager,trackingIndex,null);
    }
    static String now() { return Instant.now().toString(); }
    static String id() { return UUID.randomUUID().toString(); }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static void require(boolean condition,String message) { if(!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o) { return o instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)o) : new LinkedHashMap<>(); }
    String encode(Object o) { try { return json.writeValueAsString(o); } catch(Exception e) { throw new IllegalArgumentException("无法保存配置",e); } }
    Map<String,Object> decode(Object o) { try { return o==null ? new LinkedHashMap<>() : json.readValue(str(o),new TypeReference<LinkedHashMap<String,Object>>(){}); } catch(Exception e) { throw new IllegalStateException("历史记录损坏",e); } }
    Map<String,Object> row(String table,Long user,String id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM "+table+" WHERE user_id=? AND id=?",user,id);
        if(rows.isEmpty() || "DELETED".equals(rows.get(0).get("status"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在、已删除或无权访问");
        return rows.get(0);
    }
    Map<String,Object> object(Long u,String kind,String id) { Map<String,Object> r=row("quant_v2_object",u,id); if("DELETED".equals(r.get("status"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录已删除"); require(kind.equals(r.get("kind")),"记录类型不匹配"); return r; }
    Map<String,Object> lockObject(Long user,String kind,String identity) {
        db.update("UPDATE quant_v2_object SET revision=revision WHERE user_id=? AND id=? AND kind=?",user,identity,kind);
        return object(user,kind,identity);
    }
    Map<String,Object> view(Map<String,Object> r) {
        Map<String,Object> out=decode(r.get("payload"));
        for(var e:r.entrySet()) if(!Set.of("payload","request_json","result_json","claim_token","lease_until","user_id").contains(e.getKey())) out.put(camel(e.getKey()),e.getValue());
        if("strategies".equals(r.get("kind"))) {
            var latest=db.queryForList("SELECT * FROM quant_v2_task WHERE user_id=? AND strategy_id=? AND kind='backtests' AND status<>'DELETED' AND NOT EXISTS (SELECT 1 FROM quant_v2_experiment_run_attempt a WHERE a.task_id=quant_v2_task.id) ORDER BY created_at DESC LIMIT 1",r.get("user_id"),r.get("id"));
            out.put("latestBacktest",latest.isEmpty()?null:view(latest.get(0)));
            var versions=db.queryForList("SELECT id,version,created_at FROM quant_v2_version WHERE user_id=? AND object_id=? ORDER BY version DESC LIMIT 1",r.get("user_id"),r.get("id"));
            out.put("latestVersion",versions.isEmpty()?null:versions.get(0));
            out.put("deployments",db.queryForList("SELECT id,name,status FROM quant_v2_deployment WHERE user_id=? AND strategy_id=? AND status<>'DELETED' ORDER BY created_at DESC",r.get("user_id"),r.get("id")));
        }
        if(r.containsKey("result_json")) { Map<String,Object> response=decode(r.get("result_json")); out.put("result",response.getOrDefault("result",Map.of())); out.put("qualification",response.getOrDefault("qualification",Map.of())); out.put("state",response.getOrDefault("state",Map.of())); out.put("modelRef",response.get("modelRef")); out.put("progress","SUCCEEDED".equals(r.get("status"))?100:null); }
        return out;
    }
    static String camel(String s) { StringBuilder b=new StringBuilder(); boolean upper=false; for(char c:s.toCharArray()) { if(c=='_') upper=true; else {b.append(upper?Character.toUpperCase(c):c); upper=false;} } return b.toString(); }
    public List<Map<String,Object>> list(Long u,String kind) {
        String table=OBJECTS.contains(kind)?"quant_v2_object":TASKS.contains(kind)?"quant_v2_task":"deployments".equals(kind)?"quant_v2_deployment":null;
        require(table!=null,"未知资源");
        return ("deployments".equals(kind)?db.queryForList("SELECT * FROM "+table+" WHERE user_id=? AND status<>'DELETED' ORDER BY created_at DESC",u):db.queryForList("SELECT * FROM "+table+" WHERE user_id=? AND kind=? AND status<>'DELETED' "+(TASKS.contains(kind)?"AND NOT EXISTS (SELECT 1 FROM quant_v2_experiment_run_attempt a WHERE a.task_id=quant_v2_task.id) ":"")+"ORDER BY created_at DESC",u,kind)).stream().map(this::view).toList();
    }
    public Map<String,Object> get(Long u,String kind,String id) {
        if(OBJECTS.contains(kind)) return view(object(u,kind,id));
        if(TASKS.contains(kind)) {
            var r=row("quant_v2_task",u,id);
            require(kind.equals(r.get("kind")),"任务类型不匹配");
            var out=view(r);
            if("backtests".equals(kind)) out.put("researchContext",new WorkbenchResearchContext(this).resolve(u,r));
            return out;
        }
        require("deployments".equals(kind),"未知资源");
        var out=view(row("quant_v2_deployment",u,id)); var result=map(out.get("result"));
        var history=db.queryForList("SELECT kind,payload FROM quant_v2_paper_event WHERE user_id=? AND deployment_id=? ORDER BY created_at,id",u,id);
        for(String eventKind:List.of("orders","fills","cashLedger"))result.put(eventKind,history.stream().filter(e->eventKind.equals(e.get("kind"))).map(e->decode(e.get("payload"))).toList());
        out.put("result",result);return out;
    }
    public Map<String,Object> save(Long u,String kind,String identity,Map<String,Object> body) {
        require(OBJECTS.contains(kind),"未知资源");
        return tx.execute(status->{
            String name=str(body.get("name")).trim(); require(!name.isEmpty()&&name.length()<=160,"名称必填且不超过160字");
            Map<String,Object> payload=new LinkedHashMap<>(body); for(String k:List.of("id","status","revision","createdAt","updatedAt","name","kind","latestBacktest","latestVersion","deployments","runningStatus"))payload.remove(k);
            if("universes".equals(kind) && (payload.containsKey("productIds") || payload.containsKey("presetKey"))) {
                String preset=str(payload.get("presetKey"));
                if(!preset.isBlank()) payload.put("productIds",presetProducts(preset));
                payload.remove("assetIds");
                payload.put("membershipCapability",preset.isBlank()?"STATIC":"CURRENT_SNAPSHOT");
                payload.put("membershipAsOfDate",LocalDate.now().toString());
            }
            validateObject(u,kind,payload);
            String key=identity==null?id():identity;
            if(identity==null) db.update("INSERT INTO quant_v2_object(id,user_id,kind,name,status,revision,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",key,u,kind,name,"DRAFT",1,encode(payload),now(),now());
            else {var existing=lockObject(u,kind,key); require(!"ARCHIVED".equals(existing.get("status")),"已归档记录不可编辑"); int revision=((Number)existing.get("revision")).intValue(); if(body.containsKey("revision"))require(((Number)body.get("revision")).intValue()==revision,"记录已被修改，请刷新"); require(db.update("UPDATE quant_v2_object SET name=?,payload=?,revision=revision+1,updated_at=? WHERE id=? AND user_id=? AND revision=?",name,encode(payload),now(),key,u,revision)==1,"并发修改，请刷新"); }
            Map<String,Object> saved=get(u,kind,key);
            if("universes".equals(kind) && payload.containsKey("productIds")) persistMembership(u,key,
                    ((Number)saved.get("revision")).intValue(),payload);
            return saved;
        });
    }
    void validateObject(Long u,String kind,Map<String,Object> p) {
        if("universes".equals(kind)) {
            String assetClass=str(p.get("assetClass")); require(Set.of("STOCK","ETF","FUND").contains(assetClass),"请选择股票、ETF或场外基金");
            if(p.containsKey("productIds")) {
                List<Long> ids=productIds(p);
                require(!ids.isEmpty() && ids.size()<=10000,"资产池需包含1至10000个标的");
                require(new HashSet<>(ids).size()==ids.size(),"资产不能重复");
                var products=productsById(ids);
                for(var product:products.values()) require(assetClass.equals(assetClass(product)),"资产池不能混合资产类别");
                require(products.size()==ids.size(),"市场证券不存在");
                return;
            }
            require(p.get("assetIds") instanceof List<?> && !((List<?>)p.get("assetIds")).isEmpty() && ((List<?>)p.get("assetIds")).size()<=100,"资产池需包含1至100个标的");
            Set<String> ids=new HashSet<>(); for(Object id:(List<?>)p.get("assetIds")) require(ids.add(str(id)),"资产不能重复");
            for(var member:members(u,p)) require(assetClass.equals(member.get("assetClass")),"资产池不能混合资产类别");
        } else if("strategies".equals(kind)) {
            var pool=object(u,"universes",str(p.get("universeId"))); require(!"ARCHIVED".equals(pool.get("status")),"资产池已归档");
            var c=map(p.get("config")); if(c.containsKey("factors"))validateFactors(c.get("factors"),str(decode(pool.get("payload")).get("assetClass"))); require(Set.of("TREND","MULTI_FACTOR","ML_ELASTIC_NET","ML_XGBOOST").contains(str(c.get("strategyType"))),"请选择有效策略模板");
            if(p.get("factorSetId")!=null&&!str(p.get("factorSetId")).isBlank()) {var f=object(u,"factors",str(p.get("factorSetId")));require(!"ARCHIVED".equals(f.get("status")),"因子集已归档");require(Objects.equals(decode(f.get("payload")).get("assetClass"),decode(pool.get("payload")).get("assetClass")),"因子集与资产池类别不匹配");}
        } else { require(Set.of("STOCK","ETF","FUND").contains(str(p.get("assetClass"))),"请选择资产类别"); validateFactors(p.get("factors"),str(p.get("assetClass"))); }
    }
    void validateFactors(Object value,String assetClass) {
        require(value instanceof List<?> && !((List<?>)value).isEmpty(),"请选择因子");
        Set<String> selected=new HashSet<>();boolean nonzero=false;
        for(Object item:(List<?>)value) {var factor=map(item);String key=str(factor.get("key"));require(Set.of("momentum","trend","volatility","drawdown","reversal","volume","liquidity").contains(key),"不可用因子："+key);require(selected.add(key),"因子不能重复");require(!"FUND".equals(assetClass)||!Set.of("volume","liquidity").contains(key),"场外基金不能使用成交量或流动性因子");require(factor.getOrDefault("weight",1) instanceof Number,"因子权重需为数字");double weight=((Number)factor.getOrDefault("weight",1)).doubleValue();require(Double.isFinite(weight)&&Math.abs(weight)<=100,"因子权重必须在-100至100之间");nonzero|=weight!=0;}
        require(nonzero,"至少一个因子权重不为0");
    }
    public Map<String,Object> copy(Long u,String kind,String identity) { var r=get(u,kind,identity); r.put("name",str(r.get("name"))+" 副本"); return save(u,kind,null,r); }
    public Map<String,Object> archive(Long u,String kind,String identity) { return tx.execute(s->{ lockObject(u,kind,identity); if("factors".equals(kind)){
            for(String table:List.of("quant_v2_task","quant_v2_deployment"))for(var run:db.queryForList("SELECT request_json FROM "+table+" WHERE user_id=? AND status IN ('QUEUED','RUNNING','PAUSED','STOPPING')",u)){
                var req=decode(run.get("request_json"));if(req.get("factorVersionId")!=null){var version=row("quant_v2_version",u,str(req.get("factorVersionId")));require(!identity.equals(str(version.get("object_id"))),"请先停止关联任务和模拟组合");}
            }
        } String column="strategies".equals(kind)?"strategy_id":"universe_id"; if(!"factors".equals(kind))require(db.queryForObject("SELECT COUNT(*) FROM quant_v2_deployment WHERE user_id=? AND "+column+"=? AND status NOT IN ('STOPPED','DELETED')",Integer.class,u,identity)==0,"请先停止关联模拟组合"); require(db.queryForObject("SELECT COUNT(*) FROM quant_v2_task WHERE user_id=? AND (strategy_id=? OR universe_id=?) AND status IN ('QUEUED','RUNNING')",Integer.class,u,identity,identity)==0,"请先取消关联任务"); db.update("UPDATE quant_v2_object SET status='ARCHIVED',updated_at=? WHERE id=? AND user_id=?",now(),identity,u);return get(u,kind,identity); }); }
    public void deleteStrategy(Long user, String identity) {
        delete(user, "strategies", identity);
    }
    private void requireOrdinaryTask(Long user,String taskId){require(db.queryForObject("SELECT COUNT(*) FROM quant_v2_experiment_run_attempt WHERE user_id=? AND task_id=?",Integer.class,user,taskId)==0,"实验任务请通过参数敏感性实验管理，不能独立操作或部署");}
    public void delete(Long user, String kind, String identity) {
        if(TASKS.contains(kind))requireOrdinaryTask(user,identity);
        require(OBJECTS.contains(kind) || TASKS.contains(kind) || "deployments".equals(kind), "未知资源");
        tx.executeWithoutResult(status -> {
            if (OBJECTS.contains(kind)) {
                lockObject(user, kind, identity);
                if (!"strategies".equals(kind)) {
                    String reference = "universes".equals(kind) ? "universeId" : "factorSetId";
                    for (var strategy : db.queryForList("SELECT name,payload FROM quant_v2_object WHERE user_id=? AND kind='strategies' AND status NOT IN ('ARCHIVED','DELETED')", user)) {
                        require(!identity.equals(str(decode(strategy.get("payload")).get(reference))),
                                "策略“" + strategy.get("name") + "”仍在使用，请先更换配置、归档或删除该策略");
                    }
                }
                archive(user, kind, identity);
                db.update("UPDATE quant_v2_object SET status='DELETED',updated_at=? WHERE id=? AND user_id=?", now(), identity, user);
                return;
            }
            String table = TASKS.contains(kind) ? "quant_v2_task" : "quant_v2_deployment";
            db.update("UPDATE " + table + " SET updated_at=updated_at WHERE id=? AND user_id=?", identity, user);
            var record = row(table, user, identity);
            if (TASKS.contains(kind)) {
                require(kind.equals(record.get("kind")), "任务类型不匹配");
                require(Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(str(record.get("status"))), "请先取消任务，等待任务结束后再删除");
            } else {
                require("STOPPED".equals(record.get("status")), "请先停止模拟组合，等待清仓和结算完成后再删除");
            }
            db.update("UPDATE " + table + " SET status='DELETED',updated_at=? WHERE id=? AND user_id=?", now(), identity, user);
        });
    }
    String freeze(Long u,Map<String,Object> object) {
        String identity=str(object.get("id")); int rev=((Number)object.get("revision")).intValue();
        var rows=db.queryForList("SELECT id FROM quant_v2_version WHERE user_id=? AND object_id=? AND version=?",u,identity,rev);
        if(!rows.isEmpty())return str(rows.get(0).get("id"));
        String key=id(); db.update("INSERT INTO quant_v2_version(id,user_id,object_id,version,payload,created_at) VALUES(?,?,?,?,?,?)",key,u,identity,rev,encode(frozenPayload(object)),now());return key;
    }
    Map<String,Object> frozenPayload(Map<String,Object> object) {
        Map<String,Object> value=decode(object.get("payload"));
        for(String key:List.of("id","name","revision","status"))value.put(key,object.get(key));
        return value;
    }
    public List<Map<String,Object>> versions(Long u,String kind,String identity) { object(u,kind,identity); return db.queryForList("SELECT * FROM quant_v2_version WHERE user_id=? AND object_id=? ORDER BY version DESC",u,identity).stream().map(this::view).toList(); }
    private static final Set<String> DOMESTIC_MARKETS = Set.of("SSE", "SZSE", "BSE", "FUND_CN");
    public List<Map<String,Object>> assets(Long u) {
        return db.queryForList("SELECT a.id,a.product_id,p.name,p.code,p.product_type,p.market,p.history_coverage_complete,"
                + "(SELECT MIN(q.trade_date) FROM product_daily_quote q WHERE q.product_id=p.id AND q.adjust_type='NONE') AS history_start_date,"
                + "(SELECT MAX(q.trade_date) FROM product_daily_quote q WHERE q.product_id=p.id AND q.adjust_type='NONE') AS history_end_date,"
                + "(SELECT COUNT(*) FROM product_daily_quote q WHERE q.product_id=p.id AND q.adjust_type='NONE') AS observations "
                + "FROM investment_asset a JOIN investment_product p ON p.id=a.product_id WHERE a.user_id=? AND a.deleted=0 ORDER BY p.name", u)
                .stream().filter(r -> DOMESTIC_MARKETS.contains(str(r.get("market")).toUpperCase(Locale.ROOT)))
                .map(r -> { Map<String,Object> out=new LinkedHashMap<>(); r.forEach((k,v)->out.put(camel(k),v));
                    out.put("assetClass",assetClass(r)); return out; }).toList();
    }
    String assetClass(Map<String,Object> r) {
        String type=str(r.get("product_type")).toUpperCase(Locale.ROOT);
        // ETF feeder funds remain OTC funds; a name containing ETF is not an exchange listing.
        return Set.of("FUND", "MUTUAL_FUND").contains(type) ? "FUND" : type;
    }
    List<Long> productIds(Map<String,Object> pool) {
        require(pool.get("productIds") instanceof List<?>,"请选择市场证券");
        List<Long> ids=new ArrayList<>();
        for(Object value:(List<?>)pool.get("productIds")) {
            require(value instanceof Number && ((Number)value).longValue()>0,"市场证券编号无效");
            ids.add(((Number)value).longValue());
        }
        return ids;
    }
    Map<Long,Map<String,Object>> productsById(List<Long> ids) {
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();
        for(int from=0;from<ids.size();from+=500) {
            List<Long> part=ids.subList(from,Math.min(from+500,ids.size()));
            String marks=String.join(",",Collections.nCopies(part.size(),"?"));
            for(var product:db.queryForList("SELECT id AS product_id,product_type,market,name,code FROM investment_product WHERE id IN ("+marks+")",part.toArray()))
                result.put(((Number)product.get("product_id")).longValue(),product);
        }
        return result;
    }
    List<Long> presetProducts(String preset) {
        require(Set.of("ALL_A","CSI300","CSI500","CSI1000","SP500","NASDAQ100").contains(preset),"未知预定义资产池");
        if("ALL_A".equals(preset)) return db.queryForList("SELECT id FROM investment_product WHERE product_type='STOCK' AND market IN ('SSE','SZSE','BSE') AND status='ACTIVE' ORDER BY id",Long.class);
        require(!Set.of("SP500","NASDAQ100").contains(preset),"该指数成分数据源尚不可用，不能伪造资产池");
        require(marketProvider!=null,"指数成分数据源不可用");
        List<String> codes=marketProvider.marketUniverseMembers(preset);
        require(!codes.isEmpty(),"指数成分为空");
        Set<String> requested=new HashSet<>(codes);
        List<Long> result=new ArrayList<>();
        for(var row:db.queryForList("SELECT id,code FROM investment_product WHERE product_type='STOCK' AND market IN ('SSE','SZSE','BSE') AND status='ACTIVE'"))
            if(requested.remove(str(row.get("code")))) result.add(((Number)row.get("id")).longValue());
        require(requested.isEmpty(),"市场目录缺少指数成分，请先完成目录同步");
        return result;
    }
    void persistMembership(Long user,String universeId,int revision,Map<String,Object> payload) {
        List<Long> ids=productIds(payload);
        String snapshotId=id();
        db.update("INSERT INTO quant_universe_snapshot(id,user_id,universe_id,revision,as_of_date,capability,source,created_at) VALUES(?,?,?,?,?,?,?,?)",
                snapshotId,user,universeId,revision,LocalDate.parse(str(payload.get("membershipAsOfDate"))),
                payload.get("membershipCapability"),payload.get("presetKey")==null?"MANUAL":payload.get("presetKey"),LocalDateTime.now());
        List<Object[]> members=ids.stream().map(productId->new Object[]{snapshotId,productId}).toList();
        db.batchUpdate("INSERT INTO quant_universe_member(snapshot_id,product_id) VALUES(?,?)",members);
    }
    List<Map<String,Object>> members(Long user,Map<String,Object> pool) {
        if(pool.containsKey("productIds")) {
            List<Long> ids=productIds(pool);
            Map<Long,Map<String,Object>> byId=productsById(ids);
            require(byId.size()==ids.size(),"资产池中有市场证券已不存在");
            return ids.stream().map(byId::get).map(row->{Map<String,Object> result=new LinkedHashMap<>(row);
                result.put("assetClass",assetClass(row));return result;}).toList();
        }
        List<?> assetIds=(List<?>)pool.get("assetIds");
        String marks=String.join(",",Collections.nCopies(assetIds.size(),"?"));
        List<Object> args=new ArrayList<>(assetIds); args.add(user);
        Map<String,Map<String,Object>> found=new HashMap<>();
        for(var row:db.queryForList("SELECT a.id,a.product_id,p.product_type,p.market,p.name,p.code "
                +"FROM investment_asset a JOIN investment_product p ON p.id=a.product_id "
                +"WHERE a.id IN ("+marks+") AND a.user_id=? AND a.deleted=0",args.toArray())) {
            require(DOMESTIC_MARKETS.contains(str(row.get("market")).toUpperCase(Locale.ROOT)),"首版仅支持国内市场标的");
            row.put("assetClass",assetClass(row));found.put(str(row.get("id")),row);
        }
        require(found.size()==assetIds.size(),"资产池中有标的已不存在或不属于当前用户");
        return assetIds.stream().map(identity->found.get(str(identity))).toList();
    }
    Map<String,Object> asset(Long u,Object identity) {
        var rows=db.queryForList("SELECT a.id,a.product_id,p.product_type,p.market,p.name,p.code FROM investment_asset a "
                + "JOIN investment_product p ON a.product_id=p.id WHERE a.id=? AND a.user_id=? AND a.deleted=0",identity,u);
        require(!rows.isEmpty(),"标的不存在或不属于当前用户");
        var r=rows.get(0);
        require(DOMESTIC_MARKETS.contains(str(r.get("market")).toUpperCase(Locale.ROOT)),"首版仅支持国内市场标的");
        r.put("assetClass",assetClass(r)); return r;
    }
    List<Map<String,Object>> snapshot(Long u,Map<String,Object> pool,String end) {
        List<Map<String,Object>> result=new ArrayList<>();
        List<Map<String,Object>> selected=members(u,pool);
        require(selected.size()<=100,"当前任务最多运行100个标的；完整资产池可供后续因子研究读取");
        List<Object> ids=selected.stream().map(member->member.get("product_id")).distinct().toList();
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        List<Object> arguments=new ArrayList<>(ids); arguments.add(end);
        Map<Object,List<Map<String,Object>>> quoteGroups=new HashMap<>();
        for(var quote:db.queryForList("SELECT product_id,trade_date,open_price,high_price,low_price,close_price,previous_close,total_return_index,volume,amount,adjust_type,source "
                +"FROM product_daily_quote WHERE product_id IN ("+marks+") AND trade_date<=? ORDER BY product_id,trade_date,adjust_type",arguments.toArray()))
            quoteGroups.computeIfAbsent(quote.get("product_id"),ignored->new ArrayList<>()).add(quote);
        for(var a:selected) {
            var quotes=quoteGroups.getOrDefault(a.get("product_id"),List.of());
            Map<String,Map<String,Object>> adjusted=new HashMap<>();
            for(var q:quotes) if("QFQ".equals(str(q.get("adjust_type")))) adjusted.put(str(q.get("trade_date")),q);
            List<Map<String,Object>> bars=new ArrayList<>();
            for(var q:quotes) {
                if(!"NONE".equals(str(q.get("adjust_type")))) continue;
                String day=str(q.get("trade_date"));
                Map<String,Object> b=new LinkedHashMap<>();
                b.put("date",day); b.put("open",q.get("open_price")); b.put("close",q.get("close_price"));
                b.put("high",q.get("high_price")); b.put("low",q.get("low_price")); b.put("previousClose",q.get("previous_close"));
                Object research="FUND".equals(a.get("assetClass")) ? q.get("total_return_index")
                        : adjusted.getOrDefault(day,Map.of()).get("close_price");
                b.put("researchClose",research!=null?research:q.get("close_price"));
                b.put("volume",q.get("volume")); b.put("amount",q.get("amount"));
                b.put("adjustType","NONE"); b.put("source",q.get("source")); bars.add(b);
            }
            Map<String,Object> item=new LinkedHashMap<>(); item.put("id",str(a.get("id") == null ? a.get("product_id") : a.get("id")));
            item.put("name",a.get("name")); item.put("code",a.get("code")); item.put("assetClass",a.get("assetClass"));
            item.put("corporateActionsVerified",false); item.put("bars",bars); result.add(item);
        }
        return result;
    }
    public Map<String,Object> evaluationWindow(Long userId, String universeId, String startValue, String endValue) {
        var universe = object(userId, "universes", universeId);
        require(!"ARCHIVED".equals(universe.get("status")), "资产池已归档");
        LocalDate end = dateOrToday(endValue, "结束日期");
        LocalDate start = blank(startValue) ? null : dateOrToday(startValue, "开始日期");
        require(start == null || !start.isAfter(end), "开始日期不能晚于结束日期");
        var pool = decode(universe.get("payload"));
        List<LocalDate> shared = commonObservedDates(userId, pool, end);
        List<LocalDate> selected = start == null ? shared : shared.stream().filter(day -> !day.isBefore(start)).toList();
        List<Map<String,Object>> suggestions = new ArrayList<>();
        for (int days : List.of(60, 120, 250)) {
            boolean ready = shared.size() >= days;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("days", days);
            item.put("ready", ready);
            item.put("startDate", ready ? shared.get(days - 1).toString() : null);
            item.put("endDate", shared.isEmpty() ? null : shared.get(0).toString());
            item.put("availableEvaluationDays", shared.size());
            suggestions.add(item);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("minimumEvaluationDays", MINIMUM_EVALUATION_DAYS);
        result.put("availableEvaluationDays", shared.size());
        result.put("latestAvailableDate", shared.isEmpty() ? null : shared.get(0).toString());
        result.put("selectedEvaluationDays", start == null ? null : selected.size());
        result.put("selectedRangeReady", start != null && selected.size() >= MINIMUM_EVALUATION_DAYS);
        result.put("suggestions", suggestions);
        return result;
    }
    private List<LocalDate> commonObservedDates(Long userId, Map<String,Object> pool, LocalDate end) {
        Set<Object> distinctProductIds = new LinkedHashSet<>();
        for (Map<String,Object> member : members(userId,pool)) distinctProductIds.add(member.get("product_id"));
        require(distinctProductIds.size()<=100,"当前任务最多运行100个标的；完整资产池可供后续因子研究读取");
        List<Object> productIds = new ArrayList<>(distinctProductIds);
        require(!productIds.isEmpty(), "资产池至少包含一个标的");
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(productIds);
        parameters.add(end.toString());
        parameters.add(productIds.size());
        String sql = "SELECT trade_date FROM product_daily_quote WHERE product_id IN (" + placeholders + ") "
                + "AND adjust_type='NONE' AND trade_date<=? GROUP BY trade_date "
                + "HAVING COUNT(DISTINCT product_id)=? ORDER BY trade_date DESC";
        return db.query(sql, parameters.toArray(), (row, index) -> LocalDate.parse(row.getString(1)));
    }
    private LocalDate dateOrToday(String value, String field) {
        if (blank(value)) return LocalDate.now();
        try { return LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式错误");
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public Map<String,Object> createTask(Long u,String kind,Map<String,Object> b) { require(TASKS.contains(kind),"未知任务");return tx.execute(s->{
        String start=str(b.get("startDate")),end=str(b.get("endDate"));try {require(!LocalDate.parse(start).isAfter(LocalDate.parse(end)),"开始日期不能晚于结束日期");require(!LocalDate.parse(end).isAfter(LocalDate.now()),"结束日期不能在未来");}catch(java.time.format.DateTimeParseException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写正确日期");}
        Map<String,Object> config=map(b.get("config")); String strategyId=null,sv=null,universeId=str(b.get("universeId")),name="因子研究";
        Map<String,Object> req=new LinkedHashMap<>();
        if(!"factor-runs".equals(kind)) {var strategy=lockObject(u,"strategies",str(b.get("strategyId")));require(!"ARCHIVED".equals(strategy.get("status")),"策略已归档");var p=decode(strategy.get("payload"));strategyId=str(strategy.get("id"));sv=freeze(u,strategy);universeId=str(p.get("universeId"));config=map(p.get("config"));name=str(strategy.get("name"));if(p.get("factorSetId")!=null&&!str(p.get("factorSetId")).isBlank()){var f=lockObject(u,"factors",str(p.get("factorSetId")));require(!"ARCHIVED".equals(f.get("status")),"因子集已归档");config.put("factors",decode(f.get("payload")).get("factors"));req.put("factorVersionId",freeze(u,f));}}
        var universe=lockObject(u,"universes",universeId);require(!"ARCHIVED".equals(universe.get("status")),"资产池已归档");var pool=decode(universe.get("payload"));if("backtests".equals(kind)){var window=evaluationWindow(u,universeId,start,end);int available=((Number)window.get("selectedEvaluationDays")).intValue();require(available>=MINIMUM_EVALUATION_DAYS,"有效评估日期不足：当前区间只有"+available+"个共同有效交易日，至少需要"+MINIMUM_EVALUATION_DAYS+"日；可使用最近"+MINIMUM_EVALUATION_DAYS+"个有效交易日。");}req.put("universeId",universeId);req.put("universeVersionId",freeze(u,universe));req.put("universeVersion",req.get("universeVersionId"));config.put("assetClass",pool.get("assetClass"));config.put("corporateActionsVerified",false);if(b.get("initialCash")!=null)config.put("initialCash",b.get("initialCash"));
        req.put("kind",switch(kind){case "training-runs"->"TRAINING";case "backtests"->"BACKTEST";default->"FACTOR_RESEARCH";});req.put("config",config);req.put("assets",snapshot(u,pool,end));req.put("startDate",start);req.put("endDate",end);req.put("strategyVersionId",sv);req.put("universe",pool);
        if ("backtests".equals(kind)) {
            var comparison = trackingIndex.resolve(members(u,pool),LocalDate.parse(start),LocalDate.parse(end));
            if (!comparison.isEmpty()) req.put("trackingIndex",comparison);
        }
        String modelTask=str(b.getOrDefault("modelTaskId",b.get("trainingRunId")));if(!modelTask.isBlank()){var trained=row("quant_v2_task",u,modelTask);require("training-runs".equals(trained.get("kind"))&&"SUCCEEDED".equals(trained.get("status")),"请选择成功的训练结果");var trainingRequest=decode(trained.get("request_json"));var tc=map(trainingRequest.get("config"));for(String key:List.of("strategyType","assetClass","predictionHorizon","factors","lookback","slowWindow"))require(Objects.equals(tc.get(key),config.get(key)),"模型配置不兼容："+key);var response=decode(trained.get("result_json"));require(response.get("modelRef")!=null,"训练没有有效模型产物");req.put("modelRef",response.get("modelRef"));req.put("modelTaskId",modelTask);}
        String key=id();db.update("INSERT INTO quant_v2_task(id,user_id,kind,name,status,stage,strategy_id,strategy_version_id,universe_id,request_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",key,u,kind,name,"QUEUED","QUEUED",strategyId,sv,universeId,encode(req),now(),now());return get(u,kind,key);
    }); }
    public Map<String,Object> cancel(Long u,String kind,String identity) {requireOrdinaryTask(u,identity);get(u,kind,identity);db.update("UPDATE quant_v2_task SET status='CANCELLED',stage='CANCELLED',claim_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",now(),identity,u);return get(u,kind,identity);}
    public Map<String,Object> retry(Long u,String kind,String identity) { return tx.execute(txStatus->{
        requireOrdinaryTask(u,identity);get(u,kind,identity);var old=row("quant_v2_task",u,identity);
        require(Set.of("FAILED","CANCELLED").contains(str(old.get("status"))),"只能重试失败或取消的任务");
        if(old.get("strategy_id")!=null)require(!"ARCHIVED".equals(lockObject(u,"strategies",str(old.get("strategy_id"))).get("status")),"策略已归档，不能重试");
        var request=decode(old.get("request_json"));
        if(request.get("factorVersionId")!=null){var version=row("quant_v2_version",u,str(request.get("factorVersionId")));require(!"ARCHIVED".equals(lockObject(u,"factors",str(version.get("object_id"))).get("status")),"因子集已归档，不能重试");}
        require(!"ARCHIVED".equals(lockObject(u,"universes",str(old.get("universe_id"))).get("status")),"资产池已归档，不能重试");
        String key=id();db.update("INSERT INTO quant_v2_task(id,user_id,kind,name,status,stage,strategy_id,strategy_version_id,universe_id,request_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",key,u,kind,old.get("name"),"QUEUED","QUEUED",old.get("strategy_id"),old.get("strategy_version_id"),old.get("universe_id"),old.get("request_json"),now(),now());return get(u,kind,key);
    }); }
    public Map<String,Object> deploy(Long u,Map<String,Object>b){requireOrdinaryTask(u,str(b.get("backtestId")));return tx.execute(s->{var task=row("quant_v2_task",u,str(b.get("backtestId")));require("backtests".equals(task.get("kind"))&&"SUCCEEDED".equals(task.get("status")),"只能部署完成的回测");var response=decode(task.get("result_json"));require("QUALIFIED".equals(map(response.get("qualification")).get("status")),"回测未通过资格验证，不能部署");var strategy=lockObject(u,"strategies",str(task.get("strategy_id")));var deployRequest=decode(task.get("request_json"));if(deployRequest.get("factorVersionId")!=null){var fv=row("quant_v2_version",u,str(deployRequest.get("factorVersionId")));require(!"ARCHIVED".equals(lockObject(u,"factors",str(fv.get("object_id"))).get("status")),"因子集已归档");}var universe=lockObject(u,"universes",str(task.get("universe_id")));require(!"ARCHIVED".equals(universe.get("status")),"资产池已归档");require(!"ARCHIVED".equals(strategy.get("status")),"策略已归档");var req=decode(task.get("request_json"));var effective=map(map(response.get("result")).get("provenance"));var c=map(effective.getOrDefault("config",req.get("config")));if(b.get("initialCash")!=null)c.put("initialCash",b.get("initialCash"));double cash=((Number)c.getOrDefault("initialCash",100000)).doubleValue();require(Double.isFinite(cash)&&cash>0&&cash<=1e12,"虚拟资金无效");req.put("config",c);req.put("kind","PAPER");String date=LocalDate.now().toString();req.put("startDate",date);req.put("endDate",date);req.put("assets",snapshot(u,map(req.get("universe")),date));Map<String,Object>state=new LinkedHashMap<>();state.put("cash",cash);state.put("positions",Map.of());state.put("pendingOrders",List.of());state.put("receivables",List.of());state.put("lastDate","");state.put("sessionCount",0);state.put("equityCurve",List.of());req.put("state",state);String key=id(),name=str(b.getOrDefault("name",task.get("name")));require(!name.isBlank()&&name.length()<=160,"组合名称必填且不超过160字");db.update("INSERT INTO quant_v2_deployment(id,user_id,name,backtest_id,strategy_id,universe_id,status,revision,request_json,result_json,next_run_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",key,u,name,task.get("id"),task.get("strategy_id"),task.get("universe_id"),"RUNNING",1,encode(req),encode(Map.of("state",state)),System.currentTimeMillis(),now(),now());return get(u,"deployments",key);});}
    public Map<String,Object> lifecycle(Long u,String identity,String action,Map<String,Object>b){return tx.execute(s->{db.update("UPDATE quant_v2_deployment SET revision=revision WHERE id=? AND user_id=?",identity,u);var r=row("quant_v2_deployment",u,identity);String status=str(r.get("status"));require(!"STOPPED".equals(status),"组合已停止");if("pause".equals(action))require("RUNNING".equals(status),"只能暂停运行中的组合");if("resume".equals(action))require("PAUSED".equals(status),"只能恢复已暂停的组合");String next=switch(action){case "pause"->"PAUSED";case "resume"->"RUNNING";case "stop"->{String mode=str(b.get("mode"));require(Set.of("KEEP","LIQUIDATE").contains(mode),"请选择保留持仓或正常清仓");yield "KEEP".equals(mode)?"STOPPED":"STOPPING";}default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知操作");};require(!"STOPPING".equals(status),"组合正在按正常规则清仓");var result=decode(r.get("result_json"));var state=map(result.get("state"));if(!"RUNNING".equals(next)){
            if(state.get("pendingOrders") instanceof List<?> pending)for(Object order:pending){var cancelled=map(order);cancelled.put("status","CANCELLED");cancelled.put("reason","组合暂停或停止");String eventKey="cancel:"+identity+":"+r.get("revision")+":"+id();db.update("INSERT INTO quant_v2_paper_event(id,user_id,deployment_id,event_key,kind,payload,created_at) VALUES(?,?,?,?,?,?,?)",id(),u,identity,eventKey,"orders",encode(cancelled),now());}
            state.put("pendingOrders",List.of());result.put("state",state);
        }
        var request=decode(r.get("request_json"));
        if("resume".equals(action)) request.put("signalStartDate",LocalDate.now().toString());
        require(db.update("UPDATE quant_v2_deployment SET status=?,result_json=?,request_json=?,revision=revision+1,claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=? WHERE id=? AND user_id=? AND revision=?",next,encode(result),encode(request),System.currentTimeMillis(),now(),identity,u,r.get("revision"))==1,"组合状态已变化，请刷新");
        var event=Map.of("action",action,"previousStatus",status,"status",next,"effectiveAt",now());
        db.update("INSERT INTO quant_v2_paper_event(id,user_id,deployment_id,event_key,kind,payload,created_at) VALUES(?,?,?,?,?,?,?)",id(),u,identity,"lifecycle:"+r.get("revision"),"lifecycle",encode(event),now());
        return get(u,"deployments",identity);});}
    public List<Map<String,Object>> events(Long u,String identity){row("quant_v2_deployment",u,identity);return db.queryForList("SELECT * FROM quant_v2_paper_event WHERE user_id=? AND deployment_id=? ORDER BY created_at,id",u,identity).stream().map(this::view).toList();}
}
