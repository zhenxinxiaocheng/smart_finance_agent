package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface InvestmentDataJobMapper extends BaseMapper<InvestmentDataJob> {

    @Update("""
            UPDATE investment_data_job
            SET status = 'RUNNING', started_at = #{now}, lease_until = #{leaseUntil},
                lease_token = #{leaseToken}, updated_at = #{now}
            WHERE id = #{id}
              AND (
                  (status IN ('QUEUED', 'RETRY_WAIT')
                   AND (next_retry_at IS NULL OR next_retry_at <= #{now}))
                  OR (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until <= #{now})
              )
            """)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE investment_data_job
            SET status = #{status}, record_count = #{recordCount},
                error_message = #{errorMessage}, finished_at = #{finishedAt},
                next_retry_at = NULL, lease_until = NULL, lease_token = NULL,
                force_refresh = 0, updated_at = #{finishedAt}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{leaseToken}
            """)
    int finish(@Param("id") Long id, @Param("leaseToken") String leaseToken,
               @Param("status") String status, @Param("recordCount") int recordCount,
               @Param("errorMessage") String errorMessage, @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE investment_data_job
            SET status = #{status}, attempt_count = #{attemptCount}, next_retry_at = #{nextRetryAt},
                lease_until = NULL, lease_token = NULL, error_message = #{errorMessage},
                finished_at = #{finishedAt}, updated_at = #{updatedAt}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{leaseToken}
            """)
    int reschedule(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                   @Param("status") String status, @Param("attemptCount") int attemptCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("errorMessage") String errorMessage,
                   @Param("finishedAt") LocalDateTime finishedAt, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE investment_data_job
            SET requested_start_date = #{requestedStartDate},
                sample_start_date = #{sampleStartDate},
                sample_end_date = #{sampleEndDate},
                coverage_complete = #{coverageComplete},
                dataset_version = #{datasetVersion}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{leaseToken}
            """)
    int updateCoverage(@Param("id") Long id,
                       @Param("leaseToken") String leaseToken,
                       @Param("requestedStartDate") LocalDate requestedStartDate,
                       @Param("sampleStartDate") LocalDate sampleStartDate,
                       @Param("sampleEndDate") LocalDate sampleEndDate,
                       @Param("coverageComplete") boolean coverageComplete,
                       @Param("datasetVersion") String datasetVersion);
}
