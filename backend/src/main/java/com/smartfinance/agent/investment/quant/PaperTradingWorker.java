package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PaperTradingWorker {
    private final QuantPaperOrderMapper orderMapper;
    private final PaperTradingService service;
    private final int batchLimit;

    public PaperTradingWorker(QuantPaperOrderMapper orderMapper,
                              PaperTradingService service,
                              @Value("${investment.quant.paper.order-batch-limit}") int batchLimit) {
        this.orderMapper = orderMapper;
        this.service = service;
        this.batchLimit = batchLimit;
    }

    @Scheduled(
            initialDelayString = "${investment.quant.paper.initial-delay-ms}",
            fixedDelayString = "${investment.quant.paper.poll-delay-ms}"
    )
    public void executePendingOrders() {
        List<QuantPaperOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<QuantPaperOrder>()
                .eq(QuantPaperOrder::getStatus, "SUBMITTED")
                .orderByAsc(QuantPaperOrder::getSubmittedAt)
                .last("LIMIT " + batchLimit));
        for (QuantPaperOrder order : orders) service.execute(order);
    }
}
