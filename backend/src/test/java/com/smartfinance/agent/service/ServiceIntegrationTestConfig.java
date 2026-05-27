package com.smartfinance.agent.service;

import com.smartfinance.agent.config.MyBatisPlusConfig;
import com.smartfinance.agent.service.impl.TransactionServiceImpl;
import com.smartfinance.agent.service.impl.UserServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan("com.smartfinance.agent.mapper")
@Import({MyBatisPlusConfig.class, UserServiceImpl.class, TransactionServiceImpl.class})
class ServiceIntegrationTestConfig {
}
