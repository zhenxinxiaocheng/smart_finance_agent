package com.smartfinance.agent.investment.quant.workbench;

import com.smartfinance.agent.common.Result;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/quant/v2")
public class WorkbenchController {
    private final WorkbenchService service;
    private final WorkbenchAnalysisClient analysis;
    public WorkbenchController(WorkbenchService service,WorkbenchAnalysisClient analysis){this.service=service;this.analysis=analysis;}
    @GetMapping("/assets") public Result<?> assets(@RequestAttribute Long userId){return Result.success(service.assets(userId));}
    @DeleteMapping("/{kind}/{id}") public Result<?> delete(@RequestAttribute Long userId,@PathVariable String kind,@PathVariable String id){service.delete(userId,kind,id);return Result.success();}
    @GetMapping("/factor-catalog") public Result<?> factors(){return Result.success(analysis.factors());}
    @GetMapping("/parameter-catalog") public Result<?> parameters(){return Result.success(analysis.parameters());}
    @GetMapping("/universes/{id}/evaluation-window") public Result<?> evaluationWindow(
            @RequestAttribute Long userId, @PathVariable String id,
            @RequestParam(required=false) String startDate, @RequestParam(required=false) String endDate){
        return Result.success(service.evaluationWindow(userId, id, startDate, endDate));
    }
    @GetMapping("/{kind}") public Result<?> list(@RequestAttribute Long userId,@PathVariable String kind){return Result.success(service.list(userId,kind));}
    @GetMapping("/{kind}/{id}") public Result<?> get(@RequestAttribute Long userId,@PathVariable String kind,@PathVariable String id){return Result.success(service.get(userId,kind,id));}
    @PostMapping("/{kind}") public Result<?> create(@RequestAttribute Long userId,@PathVariable String kind,@RequestBody Map<String,Object> body){return Result.success(WorkbenchService.OBJECTS.contains(kind)?service.save(userId,kind,null,body):"deployments".equals(kind)?service.deploy(userId,body):service.createTask(userId,kind,body));}
    @PutMapping("/{kind}/{id}") public Result<?> update(@RequestAttribute Long userId,@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object> body){return Result.success(service.save(userId,kind,id,body));}
    @GetMapping("/{kind}/{id}/versions") public Result<?> versions(@RequestAttribute Long userId,@PathVariable String kind,@PathVariable String id){return Result.success(service.versions(userId,kind,id));}
    @GetMapping("/deployments/{id}/events") public Result<?> events(@RequestAttribute Long userId,@PathVariable String id){return Result.success(service.events(userId,id));}
    @PostMapping("/{kind}/{id}/{action}") public Result<?> action(@RequestAttribute Long userId,@PathVariable String kind,@PathVariable String id,@PathVariable String action,@RequestBody(required=false) Map<String,Object> body){
        Object result;
        if("deployments".equals(kind))result=service.lifecycle(userId,id,action,body==null?Map.of():body);
        else if(WorkbenchService.OBJECTS.contains(kind))result=switch(action){case "copy"->service.copy(userId,kind,id);case "archive"->service.archive(userId,kind,id);default->throw new IllegalArgumentException("未知操作");};
        else {WorkbenchService.require(WorkbenchService.TASKS.contains(kind),"未知资源");result=switch(action){case "cancel"->service.cancel(userId,kind,id);case "retry"->service.retry(userId,kind,id);default->throw new IllegalArgumentException("未知操作");};}
        return Result.success(result);
    }
}
