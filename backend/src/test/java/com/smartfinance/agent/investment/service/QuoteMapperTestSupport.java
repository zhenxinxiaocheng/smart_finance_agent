package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

public final class QuoteMapperTestSupport {
    private QuoteMapperTestSupport() { }

    public static ProductDailyQuoteMapper create(DataSource source) {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(NoLoggingImpl.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig().setBanner(false));
        try {
            var sessions = Objects.requireNonNull(factory.getObject());
            sessions.getConfiguration().addMapper(ProductDailyQuoteMapper.class);
            return new SqlSessionTemplate(sessions).getMapper(ProductDailyQuoteMapper.class);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot create test quote mapper", error);
        }
    }
}
