package com.smartfinance.agent.service;

import com.smartfinance.agent.config.MyBatisPlusConfig;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.ChinaTradingCalendarService;
import com.smartfinance.agent.investment.service.InvestmentServiceImpl;
import com.smartfinance.agent.investment.service.InvestmentDataQualityService;
import com.smartfinance.agent.service.impl.TransactionServiceImpl;
import com.smartfinance.agent.service.impl.UserServiceImpl;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.mockito.Mockito.mock;

@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan(
        annotationClass = Mapper.class,
        basePackages = {
                "com.smartfinance.agent.mapper",
                "com.smartfinance.agent.investment.mapper",
                "com.smartfinance.agent.investment.quant"
        }
)
@Import({MyBatisPlusConfig.class, UserServiceImpl.class, TransactionServiceImpl.class,
        InvestmentServiceImpl.class, ChinaTradingCalendarService.class, InvestmentRuntimeProperties.class,
        FinanceStatisticsService.class, FinanceStatisticsCache.class})
class ServiceIntegrationTestConfig {

    @Bean
    com.smartfinance.agent.config.FinanceStatisticsCacheProperties financeStatisticsCacheProperties() {
        var properties = new com.smartfinance.agent.config.FinanceStatisticsCacheProperties();
        properties.setEnabled(false);
        return properties;
    }

    @Bean
    AnalysisServiceClient analysisServiceClient() {
        return mock(AnalysisServiceClient.class);
    }

    @Bean
    InvestmentDataQualityService investmentDataQualityService() {
        return mock(InvestmentDataQualityService.class);
    }
}
