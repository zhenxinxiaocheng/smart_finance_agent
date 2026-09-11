package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.dto.InvestmentIndexCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexReorderRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexView;

import java.util.List;

public interface InvestmentIndexService {
    List<InvestmentIndexView> list(Long userId);
    List<InvestmentIndexView> search(String keyword);
    InvestmentIndexView add(Long userId, InvestmentIndexCreateRequest request);
    void reorder(Long userId, InvestmentIndexReorderRequest request);
    void remove(Long userId, Long id);
}
