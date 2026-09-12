package com.smartfinance.agent.investment.quant.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.Map;
import java.util.List;

@Component
public class WorkbenchAnalysisClient {
    private final RestClient client;
    private final String token;
    private final long leaseMillis;
    public WorkbenchAnalysisClient(RestClient.Builder builder,
            @Value("${analysis-service.base-url:http://127.0.0.1:8090}") String url,
            @Value("${analysis-service.internal-token:dev-analysis-token}") String token,
            @Value("${quant.workbench.read-timeout:15m}") Duration timeout) {
        this.token=token;leaseMillis=timeout.toMillis()+120000;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(10000);factory.setReadTimeout((int)Math.min(Integer.MAX_VALUE,timeout.toMillis()));
        client=builder.clone().baseUrl(url).requestFactory(factory).build();
    }
    public long leaseMillis(){return leaseMillis;}
    @SuppressWarnings("unchecked") public Map<String,Object> runtimeInfo() {
        var response=client.get().uri("/quant/v2/runtime-info").header("X-Internal-Token",token).retrieve().body(Map.class);
        if(response==null)throw new IllegalStateException("分析服务未返回运行环境");return response;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> candidates(Map<String,Object> request) {
        var response=client.post().uri("/quant/v2/parameter-sensitivity/candidates").header("X-Internal-Token",token)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
        if(response==null)throw new IllegalStateException("分析服务未返回候选参数");return response;
    }
    public Map<?,?> parameters(){var result=client.get().uri("/quant/v2/parameters").header("X-Internal-Token",token).retrieve().body(Map.class);if(result==null)throw new IllegalStateException("分析服务未返回参数依据");return result;}
    @SuppressWarnings("unchecked") public Map<String,Object> execute(Map<String,Object> request) {
        Map<String,Object> response=client.post().uri("/quant/v2/execute").header("X-Internal-Token",token).contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
        if(response==null)throw new IllegalStateException("分析服务返回空结果");return response;
    }
    public List<?> factors(){List<?> result=client.get().uri("/quant/v2/factors").header("X-Internal-Token",token).retrieve().body(List.class);if(result==null)throw new IllegalStateException("分析服务未返回因子目录");return result;}
}
