package com.smartfinance.agent.investment.quant.workbench;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class WorkbenchConfigCompatibilityClientTest {
    HttpServer server;WorkbenchAnalysisClient client;int status=200;String body="{}",token,path,method,requestBody;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            token=exchange.getRequestHeaders().getFirst("X-Internal-Token");path=exchange.getRequestURI().getPath();method=exchange.getRequestMethod();
            requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            var bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        client=new WorkbenchAnalysisClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-token",Duration.ofSeconds(5));
    }
    @Test void connectionRefusedRemainsTransportError() {
        server.stop(0);
        assertThatThrownBy(()->client.validateConfig(Map.of())).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }
    @Test void readTimeoutRemainsTransportError() {
        server.removeContext("/");
        server.createContext("/",exchange->{
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,2);
            exchange.getResponseBody().flush();
            try {Thread.sleep(300);} catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        client=new WorkbenchAnalysisClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort(),"token",Duration.ofMillis(30));
        assertThatThrownBy(()->client.validateConfig(Map.of()))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class)
                .hasMessageNotContaining("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
    }
    @AfterEach void close(){server.stop(0);}
    @Test void postsFrozenConfigUnderInternalAuth() {
        body="{\"compatible\":true,\"effectiveConfig\":{\"seed\":42.0},\"runtime\":{\"codeHash\":\"current\"}}";
        var result=client.validateConfig(Map.of("config",Map.of("seed",42)));
        assertThat(result).containsEntry("compatible",true);assertThat(token).isEqualTo("test-token");
        assertThat(path).isEqualTo("/quant/v2/validate-config");assertThat(method).isEqualTo("POST");assertThat(requestBody).isEqualTo("{\"config\":{\"seed\":42}}");
    }
    @Test void structuredIncompatibilityRetainsUnderlyingReason() {
        status=422;body="{\"detail\":{\"code\":\"CURRENT_RUNTIME_CONFIG_INCOMPATIBLE\",\"reasonCode\":\"MODEL_CONFIG_MISMATCH\",\"message\":\"model mismatch\"}}";
        var result=client.validateConfig(Map.of());assertThat(result).containsEntry("compatible",false);
        assertThat((Map<String,Object>)result.get("detail")).containsEntry("reasonCode","MODEL_CONFIG_MISMATCH");
    }
    @ParameterizedTest @ValueSource(ints={401,422,503}) void unrelatedHttpFailuresKeepTheirStatus(int code) {
        status=code;body="{\"detail\":{\"code\":\"OTHER_FAILURE\"}}";
        assertThatThrownBy(()->client.validateConfig(Map.of())).isInstanceOfSatisfying(RestClientResponseException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(code));
    }
}
